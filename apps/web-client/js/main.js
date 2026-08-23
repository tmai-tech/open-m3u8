import { $ } from "./dom.js";
import { DEFAULT_AD, state } from "./state.js";
import { addBreakRow, applyCatalogPreset, bindForm, setFormPlayer, setStrategy } from "./form.js";
import { bindSeekMarks } from "./ad-markers.js";
import { bindPublicUrl } from "./public-url.js";
import { apply, bindPlayer, destroyPlayer, setOnDestroyed } from "./player.js";
import { bindOtt, refreshPoster, setCatalogApply, setOttPlay, showPoster } from "./ott.js";

setFormPlayer(destroyPlayer);
setOnDestroyed(showPoster);
setOttPlay(() => apply(true));
setCatalogApply((item) => {
  destroyPlayer();
  applyCatalogPreset(item);
});
bindForm();
bindPlayer();
bindPublicUrl();
bindSeekMarks();
bindOtt();
setStrategy(state.strategy, false);
if (!$("breakList").children.length) {
  addBreakRow(30, DEFAULT_AD, 12);
  addBreakRow(90, DEFAULT_AD, 12);
}
refreshPoster();
