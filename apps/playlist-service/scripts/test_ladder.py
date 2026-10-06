"""Ladder rename: a non-720 source must leave the v720 name before a real 720 rung."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo_packager as pack  # noqa: E402


def media_playlist(init_name, segment_prefix, n=1):
    lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-TARGETDURATION:4",
        "#EXT-X-MAP:URI=\"%s\"" % init_name,
    ]
    for i in range(n):
        lines.append("#EXTINF:4.000000,")
        lines.append("%s_%d.m4s" % (segment_prefix, i))
    lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n"


def write_rung(dest, height, init_name, segment_prefix):
    (dest / ("v%d.m3u8" % height)).write_text(
        media_playlist(init_name, segment_prefix), encoding="utf-8")
    (dest / init_name).write_bytes(b"init")
    (dest / ("%s_0.m4s" % segment_prefix)).write_bytes(b"seg")


class RenameSourceRungTest(unittest.TestCase):
    def test_moves_1080_source_off_v720(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            write_rung(dest, 720, "init.m4s", "v720")
            self.assertTrue(pack.rename_source_rung(dest, 1080))
            text = (dest / "v1080.m3u8").read_text(encoding="utf-8")
            self.assertIn('URI="init1080.m4s"', text)
            self.assertIn("v1080_0.m4s", text)
            self.assertNotIn("v720_", text)
            self.assertFalse((dest / "v720.m3u8").exists())
            self.assertFalse((dest / "init.m4s").exists())
            self.assertTrue((dest / "init1080.m4s").is_file())
            self.assertTrue((dest / "v1080_0.m4s").is_file())

    def test_real_720_stays(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            write_rung(dest, 720, "init.m4s", "v720")
            self.assertFalse(pack.rename_source_rung(dest, 720))
            self.assertTrue((dest / "v720.m3u8").is_file())
            self.assertTrue((dest / "init.m4s").is_file())

    def test_refuses_to_clobber_existing_rung(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            write_rung(dest, 720, "init.m4s", "v720")
            (dest / "v1080.m3u8").write_text("#EXTM3U\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                pack.rename_source_rung(dest, 1080)
            self.assertTrue((dest / "v720.m3u8").is_file())
            self.assertTrue((dest / "init.m4s").is_file())


class LadderChoiceTest(unittest.TestCase):
    def test_taller_source_asks_for_720_after_rename(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dest = root / "titles" / "film"
            dest.mkdir(parents=True)
            write_rung(dest, 720, "init.m4s", "v720")
            write_rung(dest, 360, "init360.m4s", "v360")
            write_rung(dest, 480, "init480.m4s", "v480")
            self.assertTrue(pack.rename_source_rung(dest, 1080))
            asked = []

            def fake_encode(ffmpeg, src, dest, height, fps, audio, root, tid):
                asked.append(height)
                write_rung(dest, height, pack.rung_init_name(height), "v%d" % height)

            pack.encode_rung = fake_encode
            try:
                pack.encode_missing_ladder_rungs("ffmpeg", Path("src.mp4"), dest, 1080, 24, True, root, "film")
            finally:
                pack.encode_rung = pack_encode
            self.assertEqual(asked, [720])

    def test_short_source_is_not_upscaled(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dest = root / "pack"
            dest.mkdir()
            write_rung(dest, 720, "init.m4s", "v720")
            write_rung(dest, 360, "init360.m4s", "v360")
            self.assertTrue(pack.rename_source_rung(dest, 400))
            asked = []

            def fake_encode(*args, **kwargs):
                asked.append(args[3])

            pack.encode_rung = fake_encode
            try:
                pack.encode_missing_ladder_rungs("ffmpeg", Path("src.mp4"), dest, 400, 24, True, root, "film")
            finally:
                pack.encode_rung = pack_encode
            self.assertEqual(asked, [])
            self.assertFalse((dest / "v720.m3u8").exists())
            self.assertTrue((dest / "v400.m3u8").is_file())

    def test_native_720_only_fills_lower_rungs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dest = root / "pack"
            dest.mkdir()
            write_rung(dest, 720, "init.m4s", "v720")
            asked = []

            def fake_encode(ffmpeg, src, dest, height, fps, audio, root, tid):
                asked.append(height)

            pack.encode_rung = fake_encode
            try:
                pack.encode_missing_ladder_rungs("ffmpeg", Path("src.mp4"), dest, 720, 24, True, root, "film")
            finally:
                pack.encode_rung = pack_encode
            self.assertEqual(asked, [360, 480])


# Keep the real encoder so tests can restore it if one fails mid-way.
pack_encode = pack.encode_rung


class AddLadderTest(unittest.TestCase):
    def test_rewrites_master_and_dash_with_real_720(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tid = "tall"
            dest = root / "titles" / tid
            dest.mkdir(parents=True)
            write_rung(dest, 720, "init.m4s", "v720")
            write_rung(dest, 360, "init360.m4s", "v360")
            write_rung(dest, 480, "init480.m4s", "v480")
            src = root / "inbox" / "done" / f"{tid}.mp4"
            src.parent.mkdir(parents=True)
            src.write_bytes(b"source")
            pack.save_catalog(root, [{
                "id": tid,
                "durationSec": 112,
                "sub": "Local · 112s · 360p–1080p",
            }])

            def fake_probe(ffprobe, src):
                return 112.0, 24, True

            def fake_size(ffprobe, path):
                name = Path(path).name
                if name == "init1080.m4s":
                    return 1920, 1080
                if name == "init360.m4s":
                    return 640, 360
                if name == "init480.m4s":
                    return 854, 480
                if name == "init.m4s":
                    if (Path(path).parent / "init1080.m4s").is_file():
                        return 1280, 720
                    return 1920, 1080
                if name.endswith(".mp4"):
                    return 1920, 1080
                return 0, 0

            def fake_encode(ffmpeg, src, dest, height, fps, audio, root, tid):
                self.assertEqual(height, 720)
                write_rung(dest, 720, "init.m4s", "v720")

            orig = (pack.probe, pack.probe_video_size, pack.encode_rung)
            pack.probe, pack.probe_video_size, pack.encode_rung = fake_probe, fake_size, fake_encode
            try:
                pack.add_ladder(root, "ffmpeg", "ffprobe", tid)
            finally:
                pack.probe, pack.probe_video_size, pack.encode_rung = orig

            master = (dest / "master.m3u8").read_text(encoding="utf-8")
            self.assertIn("RESOLUTION=1280x720", master)
            self.assertIn("RESOLUTION=1920x1080", master)
            self.assertIn("v720.m3u8", master)
            self.assertIn("v1080.m3u8", master)
            mpd = (dest / "manifest.mpd").read_text(encoding="utf-8")
            self.assertIn('id="v720"', mpd)
            self.assertIn('id="v1080"', mpd)
            self.assertIn('width="1280" height="720"', mpd)
            self.assertIn('initialization="init.m4s"', mpd)
            self.assertIn('initialization="init1080.m4s"', mpd)
            catalog = json.loads((root / "catalog.json").read_text(encoding="utf-8"))
            row = catalog["titles"][0]
            self.assertEqual(row["dashUrl"], "/media/titles/tall/manifest.mpd")
            self.assertIn("360p–1080p", row["sub"])
            src_text = (dest / "v1080.m3u8").read_text(encoding="utf-8")
            self.assertIn('URI="init1080.m4s"', src_text)
            self.assertIn("v1080_0.m4s", src_text)


if __name__ == "__main__":
    unittest.main()
