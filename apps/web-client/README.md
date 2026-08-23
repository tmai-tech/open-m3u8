# web-client

Static demo UI. Served by the Java playlist demo on `:8765`, or published on GitHub Pages.

- `index.html` — shell only
- `css/demo.css` — styles
- `js/` — ES modules (form, player, seek policy, markers)
- `rewrite/rewrite.js` — in-browser stitch/inject when there is no Java proxy

Playlist rewrite on localhost is still the Java server, not this folder.
