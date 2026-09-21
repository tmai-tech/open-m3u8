import { $ } from "./dom.js";
import { SAMPLES, state } from "./state.js";
import { addBreakCard, applyCatalogPreset, bindForm, setFormPlayer, setStrategy, syncProtocolSelect } from "./form.js";
import { bindSeekMarks } from "./ad-markers.js";
import { bindPublicUrl } from "./public-url.js";
import { apply, bindPlayer, destroyPlayer, setOnDestroyed } from "./player.js";
import { bindOtt, refreshPoster, setCatalogApply, setCatalogReady, setOttPlay, showPoster } from "./ott.js";

setFormPlayer(destroyPlayer);
setOnDestroyed(showPoster);
setOttPlay(() => apply(true));
setCatalogApply((item) => {
  destroyPlayer();
  applyCatalogPreset(item);
});
setCatalogReady(syncProtocolSelect);
bindForm();
if ($("contentUrl")) $("contentUrl").value = SAMPLES.cave;
bindPlayer();
bindPublicUrl();
bindSeekMarks();
bindOtt();
setStrategy("ssai", false);
if ($("breakList") && !$("breakList").children.length) {
  addBreakCard(12, [{ assetUri: SAMPLES.grok8e30, durationSec: 16 }]);
  addBreakCard(24, [
    { assetUri: SAMPLES.grok27bf, durationSec: 16 },
    { assetUri: SAMPLES.giff, durationSec: 16 },
  ]);
}
refreshPoster();
