# open-m3u8

Java library for parsing and writing HLS M3U8 playlists (MIT).

Targets [HLS](https://datatracker.ietf.org/doc/html/draft-pantos-http-live-streaming-16). Public API may change until 1.0.

## Install

```gradle
implementation 'com.iheartradio.m3u8:open-m3u8:0.2.4'
```

```xml
<dependency>
  <groupId>com.iheartradio.m3u8</groupId>
  <artifactId>open-m3u8</artifactId>
  <version>0.2.4</version>
</dependency>
```

## Parse

```java
PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8);
Playlist playlist = parser.parse();
```

## Write

```java
PlaylistWriter writer = new PlaylistWriter(outputStream, Format.EXT_M3U, Encoding.UTF_8);
writer.write(playlist);
```

Build playlists with `Builder` / `buildUpon()` on `Playlist`, `MediaPlaylist`, `TrackData`, etc.

## Docs

- Spec: [draft-pantos-http-live-streaming-16](https://datatracker.ietf.org/doc/html/draft-pantos-http-live-streaming-16)
- Issues / PRs welcome
