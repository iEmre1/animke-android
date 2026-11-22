/*
 * Birlikte İzle aracısı (Animke Android).
 *
 * Uygulama bu kodu sayfadaki BÜTÜN alt çerçevelere (fansub oynatıcıları dahil) sayfa yüklenirken
 * ekler. Tarayıcı güvenliği gereği animke.com başka sitelerin oynatıcısındaki videoya erişemez;
 * bu aracı, videonun oynat/duraklat/sar olaylarını animke.com'a bildirir ve animke.com'dan gelen
 * "şu konuma git, oynat/duraklat" komutlarını uygular. Masaüstü uygulamasındaki köprünün karşılığı.
 *
 * Güvenlik: komutlar yalnızca animke.com'dan kabul edilir; olaylar yalnızca animke.com'a gönderilir.
 * Video okumak/kontrol etmek dışında hiçbir şey yapmaz.
 */
(function () {
  if (window.top === window || window.__animkePartyAgent) return;
  window.__animkePartyAgent = true;

  var SITES = ["https://animke.com", "https://www.animke.com"];
  var suppressUntil = 0; // kendi yaptığımız değişikliklerin olayları kullanıcı işlemi sayılmasın

  function mainVideo() {
    var best = null;
    var list = document.querySelectorAll("video");
    for (var i = 0; i < list.length; i++) {
      var d = Number(list[i].duration);
      if (!isFinite(d) || d < 60) continue; // reklam/önizleme değil, bölümün kendisi
      if (!best || d > best.duration) best = list[i];
    }
    return best;
  }

  function stateOf(v) {
    return { currentTime: Number(v.currentTime) || 0, duration: Number(v.duration) || 0, paused: v.paused || v.ended };
  }

  function send(msg) {
    msg.__animkeParty = msg.__animkeParty || "event";
    for (var i = 0; i < SITES.length; i++) {
      try {
        window.top.postMessage(msg, SITES[i]); // üst sayfa animke.com değilse tarayıcı iletmez
      } catch (e) {}
    }
  }

  ["play", "pause", "seeked"].forEach(function (name) {
    document.addEventListener(
      name,
      function (e) {
        var v = e.target;
        if (!(v instanceof HTMLVideoElement) || v !== mainVideo() || Date.now() < suppressUntil) return;
        var s = stateOf(v);
        s.__animkeParty = "event";
        s.type = name;
        send(s);
      },
      true
    );
  });

  window.addEventListener("message", function (e) {
    if (SITES.indexOf(e.origin) < 0) return;
    var d = e.data;
    if (!d || typeof d !== "object" || typeof d.__animkeParty !== "string") return;
    var v = mainVideo();
    if (!v) return; // bu çerçevede bölüm videosu yok: başka çerçeve cevap verir

    if (d.__animkeParty === "get") {
      var s = stateOf(v);
      s.__animkeParty = "state";
      s.id = d.id;
      send(s);
      return;
    }

    if (d.__animkeParty === "control") {
      var pos = Number(d.position);
      if (!isFinite(pos) || pos < 0 || pos > 6 * 3600) return;
      suppressUntil = Date.now() + 1500;
      if (Math.abs(v.currentTime - pos) > 1) v.currentTime = Math.max(0, Math.min(pos, v.duration - 1));
      var ack = function (ok) {
        send({ __animkeParty: "ack", id: d.id, ok: ok });
      };
      if (d.playing && v.paused) {
        var p = v.play();
        if (p && typeof p.then === "function") p.then(function () { ack(true); }, function () { ack(false); });
        else ack(true);
      } else {
        if (!d.playing && !v.paused) v.pause();
        ack(true);
      }
    }
  });
})();
