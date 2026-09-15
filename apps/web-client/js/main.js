import { state } from "./state.js";
import { applyCatalogPreset, bindForm, setFormPlayer, setStrategy } from "./form.js";
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
refreshPoster();
