/* NEO AI — UI + procedural 3D avatar (three.js r149, bundled offline) */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var bridge = window.NeoNative || null;

  var state = {
    server: 'http://127.0.0.1:8787',
    token: '',
    language: 'hi-IN',
    theme: 'dark',
    speak: true,
    handsFree: false,
    automation: false,
    accessibility: false,
    name: 'Rehan',
    online: false,
    mode: 'idle'
  };

  var PERM_LABELS = {
    'android.permission.RECORD_AUDIO': 'Microphone',
    'android.permission.READ_CONTACTS': 'Contacts',
    'android.permission.CALL_PHONE': 'Phone calls',
    'android.permission.CAMERA': 'Camera / torch',
    'android.permission.READ_CALENDAR': 'Calendar read',
    'android.permission.ACCESS_COARSE_LOCATION': 'Location (weather)',
    'android.permission.POST_NOTIFICATIONS': 'Notifications'
  };

  function call(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!bridge || typeof bridge[name] !== 'function') return null;
    try { return bridge[name].apply(bridge, args); } catch (e) { console.warn('bridge', name, e); return null; }
  }

  function toast(text) {
    var wrap = $('toasts');
    var el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    wrap.appendChild(el);
    setTimeout(function () {
      el.style.opacity = '0';
      el.style.transition = 'opacity .3s';
      setTimeout(function () { el.remove(); }, 320);
    }, 2400);
  }

  // ═════════════════════════════════════════════════════════ avatar

  function makeIrisTexture() {
    var c = document.createElement('canvas');
    c.width = c.height = 128;
    var g = c.getContext('2d');
    var grad = g.createRadialGradient(64, 64, 6, 64, 64, 62);
    grad.addColorStop(0, '#d9ab74');
    grad.addColorStop(0.32, '#9a6634');
    grad.addColorStop(0.72, '#55301a');
    grad.addColorStop(1, '#1d0e06');
    g.fillStyle = grad;
    g.beginPath(); g.arc(64, 64, 62, 0, Math.PI * 2); g.fill();
    g.globalAlpha = 0.35;
    for (var i = 0; i < 46; i++) {
      var a = (i / 46) * Math.PI * 2;
      g.strokeStyle = i % 3 === 0 ? '#f3d2a6' : '#2a150a';
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(64 + Math.cos(a) * 16, 64 + Math.sin(a) * 16);
      g.lineTo(64 + Math.cos(a) * 58, 64 + Math.sin(a) * 58);
      g.stroke();
    }
    var tex = new THREE.CanvasTexture(c);
    tex.encoding = THREE.sRGBEncoding;
    return tex;
  }

  function makeSoftTexture(inner, outer) {
    var c = document.createElement('canvas');
    c.width = c.height = 128;
    var g = c.getContext('2d');
    var grad = g.createRadialGradient(64, 64, 2, 64, 64, 63);
    grad.addColorStop(0, inner);
    grad.addColorStop(0.45, outer);
    grad.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, 128, 128);
    var tex = new THREE.CanvasTexture(c);
    tex.encoding = THREE.sRGBEncoding;
    return tex;
  }

  function NeoAvatar(canvas) {
    this.canvas = canvas;
    this.mode = 'idle';
    this.amp = 0;
    this.targetAmp = 0;
    this.blinkAt = 2 + Math.random() * 3;
    this.blinkT = 0;
    this.smile = 0.15;
    this.happyUntil = 0;
    this.yaw = 0;
    this.pitch = 0;
    this.targetYaw = 0;
    this.targetPitch = 0;
    this.dragging = false;
    this.lastPointer = null;
    this.audioActive = false;
    this.t = 0;
    this.init();
  }

  NeoAvatar.prototype.init = function () {
    var self = this;
    this.renderer = new THREE.WebGLRenderer({
      canvas: this.canvas,
      antialias: true,
      alpha: true,
      powerPreference: 'high-performance'
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.outputEncoding = THREE.sRGBEncoding;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.08;

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(32, 1, 0.1, 20);
    this.camera.position.set(0, 1.0, 2.35);
    this.camera.lookAt(0, 0.86, 0);

    this.root = new THREE.Group();
    this.root.position.y = 0;
    this.scene.add(this.root);

    this.buildLights();
    this.buildCharacter();
    this.buildAtmosphere();
    this.resize();
    window.addEventListener('resize', function () { self.resize(); });

    this.bindPointer();
    this.clock = new THREE.Clock();
    this.loop();
  };

  NeoAvatar.prototype.resize = function () {
    var w = window.innerWidth || 360;
    var h = window.innerHeight || 640;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  };

  NeoAvatar.prototype.buildLights = function () {
    var hemi = new THREE.HemisphereLight(0xcfd8ff, 0x2a1838, 0.62);
    this.scene.add(hemi);

    var key = new THREE.DirectionalLight(0xffe3d0, 1.75);
    key.position.set(1.2, 2.2, 2.4);
    this.scene.add(key);

    var fill = new THREE.DirectionalLight(0x7fa8ff, 0.85);
    fill.position.set(-2.0, 1.1, 1.2);
    this.scene.add(fill);

    var rim = new THREE.PointLight(0xa855f7, 2.2, 6, 2);
    rim.position.set(-0.9, 1.9, -1.1);
    this.scene.add(rim);

    var rim2 = new THREE.PointLight(0x00e0c6, 1.4, 5, 2);
    rim2.position.set(1.1, 0.9, -0.9);
    this.scene.add(rim2);

    var faceFill = new THREE.PointLight(0xffd9c9, 0.5, 3, 2);
    faceFill.position.set(0, 1.35, 0.9);
    this.scene.add(faceFill);
  };

  NeoAvatar.prototype.buildCharacter = function () {
    var M = {
      skin: new THREE.MeshStandardMaterial({ color: 0xf0c1a4, roughness: 0.58, metalness: 0.03 }),
      skinDeep: new THREE.MeshStandardMaterial({ color: 0xdda184, roughness: 0.62, metalness: 0.02 }),
      dress: new THREE.MeshStandardMaterial({ color: 0x2b1b52, roughness: 0.38, metalness: 0.35 }),
      dress2: new THREE.MeshStandardMaterial({ color: 0x1c1038, roughness: 0.42, metalness: 0.3 }),
      hair: new THREE.MeshStandardMaterial({ color: 0x24121f, roughness: 0.34, metalness: 0.42 }),
      hair2: new THREE.MeshStandardMaterial({ color: 0x3b1e33, roughness: 0.3, metalness: 0.5 }),
      lip: new THREE.MeshStandardMaterial({ color: 0xb54a63, roughness: 0.34, metalness: 0.08 }),
      mouth: new THREE.MeshStandardMaterial({ color: 0x360f19, roughness: 0.7, metalness: 0 }),
      brow: new THREE.MeshStandardMaterial({ color: 0x2b1720, roughness: 0.5, metalness: 0.2 }),
      gold: new THREE.MeshStandardMaterial({ color: 0xe8c48a, roughness: 0.25, metalness: 0.95 }),
      gem: new THREE.MeshStandardMaterial({
        color: 0x7ffff0, emissive: 0x00c9b4, emissiveIntensity: 1.2,
        roughness: 0.15, metalness: 0.2
      }),
      white: new THREE.MeshStandardMaterial({ color: 0xfff8f5, roughness: 0.22, metalness: 0 }),
      black: new THREE.MeshStandardMaterial({ color: 0x140a10, roughness: 0.3, metalness: 0.1 })
    };
    this.M = M;

    var head = new THREE.Group();
    head.position.set(0, 1.15, 0);
    this.head = head;
    this.root.add(head);

    // skull
    var skull = new THREE.Mesh(new THREE.SphereGeometry(0.19, 64, 64), M.skin);
    skull.scale.set(1.0, 1.1, 0.97);
    head.add(skull);

    // jaw / chin
    var jaw = new THREE.Mesh(new THREE.SphereGeometry(0.125, 40, 32), M.skin);
    jaw.scale.set(1.02, 0.78, 0.9);
    jaw.position.set(0, -0.075, 0.028);
    head.add(jaw);

    var chin = new THREE.Mesh(new THREE.SphereGeometry(0.052, 28, 20), M.skin);
    chin.scale.set(1.1, 0.7, 0.8);
    chin.position.set(0, -0.145, 0.1);
    head.add(chin);

    // ears
    [-1, 1].forEach(function (s) {
      var ear = new THREE.Mesh(new THREE.SphereGeometry(0.036, 24, 20), M.skin);
      ear.scale.set(0.34, 0.85, 0.62);
      ear.position.set(s * 0.183, 0.0, 0.008);
      head.add(ear);

      var hoop = new THREE.Mesh(new THREE.TorusGeometry(0.014, 0.0022, 8, 24), M.gold);
      hoop.position.set(s * 0.192, -0.045, 0.012);
      hoop.rotation.y = Math.PI / 2;
      head.add(hoop);

      var gem = new THREE.Mesh(new THREE.SphereGeometry(0.0065, 16, 12), M.gem);
      gem.position.set(s * 0.192, -0.06, 0.012);
      head.add(gem);
    });

    // eyes
    this.eyeGroups = [];
    var irisTex = makeIrisTexture();
    [-1, 1].forEach(function (s) {
      var eye = new THREE.Group();
      eye.position.set(s * 0.068, 0.012, 0.162);
      head.add(eye);

      var sclera = new THREE.Mesh(new THREE.SphereGeometry(0.033, 28, 24), M.white);
      sclera.scale.set(1, 0.8, 0.62);
      eye.add(sclera);

      var iris = new THREE.Mesh(
        new THREE.CircleGeometry(0.0195, 32),
        new THREE.MeshBasicMaterial({ map: irisTex, transparent: true })
      );
      iris.position.z = 0.0225;
      eye.add(iris);

      var pupil = new THREE.Mesh(new THREE.CircleGeometry(0.0085, 24), M.black);
      pupil.position.z = 0.0232;
      eye.add(pupil);

      var glint = new THREE.Mesh(
        new THREE.CircleGeometry(0.0042, 16),
        new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.95 })
      );
      glint.position.set(0.006, 0.007, 0.0236);
      eye.add(glint);

      var lash = new THREE.Mesh(new THREE.TorusGeometry(0.031, 0.0026, 6, 20, Math.PI * 0.92), M.black);
      lash.position.set(0, 0.004, 0.021);
      lash.rotation.z = -0.06;
      eye.add(lash);

      var brow = new THREE.Mesh(new THREE.TorusGeometry(0.031, 0.003, 6, 18, Math.PI * 0.6), M.brow);
      brow.position.set(s * 0.068, 0.058, 0.168);
      brow.scale.x = s;
      brow.rotation.z = -0.42;
      brow.rotation.x = -0.14;
      head.add(brow);

      this.eyeGroups.push(eye);
    }, this);

    // nose
    var bridgeNose = new THREE.Mesh(new THREE.SphereGeometry(0.014, 20, 18), M.skinDeep);
    bridgeNose.scale.set(0.7, 1.5, 0.55);
    bridgeNose.position.set(0, -0.012, 0.178);
    head.add(bridgeNose);

    var tip = new THREE.Mesh(new THREE.SphereGeometry(0.016, 20, 18), M.skin);
    tip.scale.set(1.1, 0.85, 0.8);
    tip.position.set(0, -0.042, 0.182);
    head.add(tip);

    // mouth
    var mouth = new THREE.Group();
    mouth.position.set(0, -0.088, 0.168);
    head.add(mouth);
    this.mouth = mouth;

    var inner = new THREE.Mesh(new THREE.SphereGeometry(0.024, 24, 16), M.mouth);
    inner.scale.set(1.0, 0.32, 0.5);
    inner.position.set(0, 0, 0.008);
    mouth.add(inner);
    this.mouthInner = inner;

    var upper = new THREE.Mesh(new THREE.SphereGeometry(0.027, 28, 18), M.lip);
    upper.scale.set(1.02, 0.3, 0.38);
    upper.position.set(0, 0.006, 0.012);
    mouth.add(upper);
    this.upperLip = upper;

    var lower = new THREE.Mesh(new THREE.SphereGeometry(0.025, 28, 18), M.lip);
    lower.scale.set(0.94, 0.34, 0.42);
    lower.position.set(0, -0.008, 0.013);
    mouth.add(lower);
    this.lowerLip = lower;

    this.smileCorners = [];
    [-1, 1].forEach(function (s) {
      var corner = new THREE.Mesh(new THREE.SphereGeometry(0.005, 12, 10), M.lip);
      corner.position.set(s * 0.024, 0.002, 0.012);
      mouth.add(corner);
      this.smileCorners.push(corner);
    }, this);

    // blush
    var blushTex = makeSoftTexture('rgba(255,120,150,0.55)', 'rgba(255,90,130,0.12)');
    [-1, 1].forEach(function (s) {
      var blush = new THREE.Mesh(
        new THREE.CircleGeometry(0.036, 24),
        new THREE.MeshBasicMaterial({ map: blushTex, transparent: true, opacity: 0.42, depthWrite: false })
      );
      blush.position.set(s * 0.106, -0.052, 0.150);
      blush.rotation.y = s * 0.5;
      blush.rotation.x = -0.05;
      head.add(blush);
    });

    // neck + shoulders + dress
    var neck = new THREE.Mesh(new THREE.CylinderGeometry(0.052, 0.062, 0.15, 32), M.skin);
    neck.position.set(0, 0.965, 0.005);
    this.root.add(neck);

    var collar = new THREE.Mesh(new THREE.TorusGeometry(0.053, 0.003, 8, 40), M.gold);
    collar.rotation.x = Math.PI / 2;
    collar.position.set(0, 0.915, 0.008);
    this.root.add(collar);

    var pendantChain = new THREE.Mesh(new THREE.TorusGeometry(0.062, 0.0016, 6, 40, Math.PI), M.gold);
    pendantChain.position.set(0, 0.9, 0.055);
    pendantChain.rotation.z = Math.PI;
    this.root.add(pendantChain);

    var pendant = new THREE.Mesh(new THREE.SphereGeometry(0.0085, 16, 12), M.gem);
    pendant.position.set(0, 0.868, 0.082);
    this.root.add(pendant);
    this.pendant = pendant;

    var body = new THREE.Group();
    this.body = body;
    this.root.add(body);

    // shoulders (bare, dress starts below)
    [-1, 1].forEach(function (s) {
      var sh = new THREE.Mesh(new THREE.SphereGeometry(0.088, 32, 24), M.skin);
      sh.scale.set(1.25, 0.82, 0.92);
      sh.position.set(s * 0.155, 0.815, 0);
      body.add(sh);
    });

    var chest = new THREE.Mesh(new THREE.SphereGeometry(0.1, 32, 24), M.skin);
    chest.scale.set(1.35, 0.7, 0.8);
    chest.position.set(0, 0.79, 0.01);
    body.add(chest);

    // dress bodice
    var points = [];
    points.push(new THREE.Vector2(0.02, 0.30));
    points.push(new THREE.Vector2(0.11, 0.31));
    points.push(new THREE.Vector2(0.17, 0.42));
    points.push(new THREE.Vector2(0.20, 0.56));
    points.push(new THREE.Vector2(0.215, 0.68));
    points.push(new THREE.Vector2(0.195, 0.755));
    points.push(new THREE.Vector2(0.135, 0.78));
    points.push(new THREE.Vector2(0.0, 0.775));
    var lathe = new THREE.LatheGeometry(points, 64);
    var dress = new THREE.Mesh(lathe, M.dress);
    dress.scale.set(1, 1, 0.72);
    body.add(dress);

    // subtle neckline trim
    var neckTrim = new THREE.Mesh(new THREE.TorusGeometry(0.135, 0.0035, 8, 64), M.gem);
    neckTrim.rotation.x = Math.PI / 2;
    neckTrim.position.set(0, 0.777, 0);
    neckTrim.scale.set(1, 0.72, 1);
    body.add(neckTrim);

    // waist trim
    var waist = new THREE.Mesh(new THREE.TorusGeometry(0.194, 0.003, 8, 64), M.gold);
    waist.rotation.x = Math.PI / 2;
    waist.position.set(0, 0.50, 0);
    waist.scale.set(1, 0.72, 1);
    body.add(waist);

    // chest shape (clothed, gentle)
    [-1, 1].forEach(function (s) {
      var b = new THREE.Mesh(new THREE.SphereGeometry(0.072, 28, 22), M.dress);
      b.scale.set(0.95, 0.85, 0.55);
      b.position.set(s * 0.072, 0.70, 0.108);
      body.add(b);
    });

    // arms
    this.arms = [];
    [-1, 1].forEach(function (s) {
      var arm = new THREE.Group();
      arm.position.set(s * 0.215, 0.80, 0);
      arm.rotation.z = s * 0.28;
      body.add(arm);
      this.arms.push(arm);

      var upper = new THREE.Mesh(new THREE.CapsuleGeometry(0.034, 0.13, 8, 16), M.skin);
      upper.position.set(0, -0.09, 0);
      arm.add(upper);

      var fore = new THREE.Mesh(new THREE.CapsuleGeometry(0.029, 0.13, 8, 16), M.skin);
      fore.position.set(0, -0.235, 0.012);
      fore.rotation.x = -0.16;
      arm.add(fore);

      var hand = new THREE.Mesh(new THREE.SphereGeometry(0.032, 22, 18), M.skin);
      hand.scale.set(0.9, 1.15, 0.55);
      hand.position.set(0, -0.325, 0.022);
      arm.add(hand);
    }, this);

    // hair
    var hair = new THREE.Group();
    hair.position.set(0, 1.15, 0);
    this.hair = hair;
    this.root.add(hair);

    var cap = new THREE.Mesh(
      new THREE.SphereGeometry(0.202, 56, 40, 0, Math.PI * 2, 0, Math.PI * 0.52),
      M.hair
    );
    cap.scale.set(1.03, 1.12, 1.03);
    cap.rotation.x = -0.55;
    cap.position.set(0, 0.006, -0.004);
    hair.add(cap);

    var back = new THREE.Mesh(new THREE.SphereGeometry(0.2, 48, 32), M.hair);
    back.scale.set(1.06, 1.0, 0.85);
    back.position.set(0, -0.02, -0.055);
    back.rotation.x = 0.12;
    hair.add(back);

    function strand(pointsArr, radius, mat) {
      var curve = new THREE.CatmullRomCurve3(pointsArr.map(function (p) {
        return new THREE.Vector3(p[0], p[1], p[2]);
      }));
      var geo = new THREE.TubeGeometry(curve, 26, radius, 7, false);
      return new THREE.Mesh(geo, mat);
    }

    // bangs
    var bangSpecs = [
      [-0.135, 0.075, 0.017], [-0.078, 0.055, 0.02], [-0.022, 0.048, 0.022],
      [0.032, 0.055, 0.02], [0.09, 0.078, 0.018], [0.142, 0.1, 0.015],
      [-0.005, 0.042, 0.021]
    ];
    bangSpecs.forEach(function (b, i) {
      var x = b[0];
      var yEnd = b[1];
      var s = strand([
        [x * 0.25, 0.19, 0.02],
        [x * 0.78, 0.16, 0.135],
        [x, yEnd, 0.188]
      ], b[2], i % 3 === 0 ? M.hair2 : M.hair);
      hair.add(s);
    });

    // side + back long hair
    for (var i = 0; i < 14; i++) {
      var side = i < 2 ? (i === 0 ? -1 : 1) : 0;
      var t = (i - 2) / 11;
      var x;
      if (side !== 0) x = side * (0.16 + Math.random() * 0.02);
      else x = -0.18 + t * 0.36;
      var depth = side !== 0 ? 0.03 : -0.1 - Math.random() * 0.04;
      var len = 0.62 + Math.random() * 0.22;
      var mat = i % 4 === 0 ? M.hair2 : M.hair;
      var curve = new THREE.CatmullRomCurve3([
        new THREE.Vector3(x * 0.8, 0.19, depth + 0.03),
        new THREE.Vector3(x * 1.05, 0.02, depth - 0.02),
        new THREE.Vector3(x * 1.16, -0.24, depth - 0.01),
        new THREE.Vector3(x * 1.12, -len, depth + 0.02)
      ]);
      var geo = new THREE.TubeGeometry(curve, 28, 0.017 + Math.random() * 0.014, 7, false);
      var strandMesh = new THREE.Mesh(geo, mat);
      hair.add(strandMesh);
    }

    // hair sparkle accents
    var sparkMat = new THREE.MeshBasicMaterial({
      color: 0x9f7bff, transparent: true, opacity: 0.5
    });
    for (var k = 0; k < 5; k++) {
      var sp = new THREE.Mesh(new THREE.SphereGeometry(0.004, 8, 8), sparkMat);
      sp.position.set(-0.17 + Math.random() * 0.34, 0.16 + Math.random() * 0.06, 0.15);
      hair.add(sp);
    }

    // halo rings
    var ringMat = new THREE.MeshBasicMaterial({
      color: 0x8b6bff, transparent: true, opacity: 0.28, side: THREE.DoubleSide
    });
    var ring = new THREE.Mesh(new THREE.TorusGeometry(0.43, 0.0022, 6, 120), ringMat);
    ring.position.set(0, 1.16, -0.42);
    this.ring = ring;
    this.scene.add(ring);

    var ring2 = new THREE.Mesh(
      new THREE.TorusGeometry(0.53, 0.0014, 6, 120),
      new THREE.MeshBasicMaterial({ color: 0x00e0c6, transparent: true, opacity: 0.18, side: THREE.DoubleSide })
    );
    ring2.position.set(0, 1.16, -0.5);
    this.ring2 = ring2;
    this.scene.add(ring2);
  };

  NeoAvatar.prototype.buildAtmosphere = function () {
    var tex = makeSoftTexture('rgba(160,130,255,0.9)', 'rgba(120,90,255,0.18)');
    this.particles = [];
    var group = new THREE.Group();
    this.scene.add(group);
    for (var i = 0; i < 34; i++) {
      var mat = new THREE.SpriteMaterial({
        map: tex, transparent: true, opacity: 0.2 + Math.random() * 0.4,
        depthWrite: false, blending: THREE.AdditiveBlending
      });
      var sp = new THREE.Sprite(mat);
      var sc = 0.02 + Math.random() * 0.05;
      sp.scale.set(sc, sc, 1);
      sp.position.set((Math.random() - 0.5) * 1.7, Math.random() * 1.7, -0.2 - Math.random() * 0.9);
      sp.userData.speed = 0.012 + Math.random() * 0.035;
      group.add(sp);
      this.particles.push(sp);
    }
    var halo = new THREE.Sprite(new THREE.SpriteMaterial({
      map: makeSoftTexture('rgba(120,90,255,0.55)', 'rgba(90,60,220,0.1)'),
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending
    }));
    halo.scale.set(1.5, 1.5, 1);
    halo.position.set(0, 1.18, -0.55);
    this.scene.add(halo);
    this.halo = halo;
  };

  NeoAvatar.prototype.bindPointer = function () {
    var self = this;
    this.canvas.addEventListener('pointerdown', function (e) {
      self.dragging = true;
      self.lastPointer = { x: e.clientX, y: e.clientY };
      self.canvas.setPointerCapture && self.canvas.setPointerCapture(e.pointerId);
    });
    this.canvas.addEventListener('pointermove', function (e) {
      if (!self.dragging || !self.lastPointer) return;
      var dx = (e.clientX - self.lastPointer.x) / window.innerWidth;
      var dy = (e.clientY - self.lastPointer.y) / window.innerHeight;
      self.targetYaw = Math.max(-0.45, Math.min(0.45, self.targetYaw + dx * 1.6));
      self.targetPitch = Math.max(-0.22, Math.min(0.18, self.targetPitch + dy * 0.9));
      self.lastPointer = { x: e.clientX, y: e.clientY };
    });
    window.addEventListener('pointerup', function () { self.dragging = false; self.lastPointer = null; });
    this.canvas.addEventListener('click', function (e) {
      if (!self.dragging) self.celebrate();
      if (e && e.clientY > window.innerHeight * 0.72) return;
    });
  };

  NeoAvatar.prototype.celebrate = function () {
    this.happyUntil = this.t + 1.4;
  };

  NeoAvatar.prototype.setMode = function (mode) {
    this.mode = mode;
    if (mode === 'happy') this.celebrate();
  };

  NeoAvatar.prototype.setAmplitude = function (v) {
    this.targetAmp = Math.max(0, Math.min(1, v));
  };

  NeoAvatar.prototype.loop = function () {
    var self = this;
    requestAnimationFrame(function () { self.loop(); });
    var dt = Math.min(this.clock.getDelta(), 0.05);
    this.t += dt;
    var t = this.t;

    this.amp += (this.targetAmp - this.amp) * Math.min(1, dt * 18);

    // smooth look
    this.yaw += (this.targetYaw - this.yaw) * Math.min(1, dt * 6);
    this.pitch += (this.targetPitch - this.pitch) * Math.min(1, dt * 6);

    var mode = this.mode;
    var listening = mode === 'listening';
    var thinking = mode === 'thinking';
    var speaking = mode === 'speaking';

    // body / head idle motion
    var breath = Math.sin(t * 1.5);
    var sway = Math.sin(t * 0.7);
    this.root.position.y = breath * 0.004;
    this.body.rotation.z = sway * 0.008;
    this.body.scale.set(1 + breath * 0.004, 1 + breath * 0.006, 1);

    var headYaw = this.yaw + sway * 0.02;
    var headPitch = this.pitch;
    var headRoll = 0;
    if (listening) {
      headRoll = 0.06 + Math.sin(t * 1.1) * 0.01;
      headPitch -= 0.035;
    } else if (thinking) {
      headRoll = -0.05;
      headPitch -= 0.055;
      headYaw += 0.05;
    } else if (speaking) {
      headRoll = Math.sin(t * 2.2) * 0.012;
      headPitch += this.amp * 0.02;
    }

    this.head.rotation.y = headYaw;
    this.head.rotation.x = headPitch;
    this.head.rotation.z = headRoll;

    // hair sway
    this.hair.rotation.y = headYaw * 0.7 + Math.sin(t * 0.9) * 0.012;
    this.hair.rotation.z = Math.sin(t * 1.1) * 0.006 + headRoll * 0.4;
    this.ring.rotation.z = t * 0.05;
    this.ring2.rotation.z = -t * 0.04;
    this.halo.material.opacity = 0.35 + Math.sin(t * 1.3) * 0.08;

    // blink
    if (t > this.blinkAt) {
      this.blinkT = 1;
      this.blinkAt = t + 2.2 + Math.random() * 3.6;
      if (Math.random() < 0.25) this.blinkAt = t + 0.35;
    }
    if (this.blinkT > 0) this.blinkT = Math.max(0, this.blinkT - dt * 7.5);
    var blink = this.blinkT > 0 ? Math.sin(this.blinkT * Math.PI) : 0;
    var eyeScaleY = 1 - blink * 0.92;
    if (listening) eyeScaleY *= 1.06;
    if (thinking) eyeScaleY *= 0.92;
    this.eyeGroups.forEach(function (g) {
      g.scale.y = Math.max(0.06, eyeScaleY);
    });

    // eyes look around
    var lookX = 0;
    var lookY = 0;
    if (thinking) { lookX = -0.004; lookY = 0.006; }
    else if (listening) { lookX = 0.002; lookY = 0.002; }
    else { lookX = Math.sin(t * 0.43) * 0.0025; lookY = Math.sin(t * 0.61) * 0.0015; }
    this.eyeGroups.forEach(function (g, i) {
      var s = i === 0 ? -1 : 1;
      g.position.x = s * 0.068 + lookX;
      g.position.y = 0.012 + lookY;
    });

    // smile
    var happy = t < this.happyUntil ? 1 : 0;
    var targetSmile = happy ? 1 : (speaking ? 0.45 : (listening ? 0.5 : 0.2));
    this.smile += (targetSmile - this.smile) * Math.min(1, dt * 5);
    var s2 = this.smile;
    this.mouth.rotation.z = 0;
    this.smileCorners.forEach(function (c, i) {
      var s = i === 0 ? -1 : 1;
      c.position.y = 0.002 + s2 * 0.009;
      c.position.x = s * (0.024 + s2 * 0.0035);
    });

    // lip sync
    var open = this.amp;
    this.mouthInner.scale.y = 0.3 + open * 2.4;
    this.mouthInner.visible = open > 0.02 || s2 > 0.55;
    this.lowerLip.position.y = -0.008 - open * 0.013;
    this.upperLip.position.y = 0.006 + open * 0.002;
    this.mouthInner.position.y = -open * 0.004;
    // jaw follows the voice slightly
    this.head.position.y = 1.15 - open * 0.004;

    // happy bounce
    if (happy) {
      this.root.position.y += Math.abs(Math.sin(t * 9)) * 0.006;
      this.head.rotation.z += Math.sin(t * 7) * 0.01;
    }

    // synthetic amplitude for phone TTS (no analyser data)
    if (speaking && !this.audioActive) {
      var fake = 0.32 + Math.abs(Math.sin(t * 8.5)) * 0.28 + Math.sin(t * 17) * 0.08;
      this.amp += (Math.max(0, Math.min(0.9, fake)) - this.amp) * 0.35;
    }
    if (!speaking && !this.audioActive) this.targetAmp = 0;

    // arms settle
    this.arms.forEach(function (arm, i) {
      var s = i === 0 ? -1 : 1;
      arm.rotation.z = s * 0.28 + Math.sin(t * 0.8 + i) * 0.01;
      arm.rotation.x = Math.sin(t * 1.2 + i * 0.4) * 0.015;
    });

    // pendant glow pulse
    if (this.pendant) {
      this.pendant.scale.setScalar(1 + Math.sin(t * 2.4) * 0.08);
    }

    // particles
    this.particles.forEach(function (p) {
      p.position.y += p.userData.speed * dt * 10;
      if (p.position.y > 1.8) p.position.y = 0.05;
      p.material.opacity = 0.12 + Math.abs(Math.sin(t * 0.6 + p.position.x * 3)) * 0.35;
    });

    this.renderer.render(this.scene, this.camera);
  };

  // ═════════════════════════════════════════════════════════ audio

  var audioCtx = null;
  var analyser = null;
  var audioEl = null;
  var audioUrl = null;
  var dataArr = null;
  var lastSpoken = '';

  function ensureAudio() {
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return Promise.resolve(null);
    if (!audioCtx) audioCtx = new Ctx();
    if (audioCtx.state === 'suspended') {
      return audioCtx.resume().then(function () { return audioCtx; }).catch(function () { return audioCtx; });
    }
    return Promise.resolve(audioCtx);
  }

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  function stopAudio() {
    if (audioEl) {
      try { audioEl.pause(); } catch (e) {}
      audioEl.onended = null;
      audioEl = null;
    }
    if (audioUrl) { URL.revokeObjectURL(audioUrl); audioUrl = null; }
    analyser = null;
    dataArr = null;
    if (avatar) { avatar.audioActive = false; avatar.setAmplitude(0); }
  }

  function playAudio(b64, mime) {
    stopAudio();
    var bytes = b64ToBytes(b64);
    var blob = new Blob([bytes], { type: mime || 'audio/mpeg' });
    audioUrl = URL.createObjectURL(blob);
    var el = new Audio();
    el.src = audioUrl;
    el.playsInline = true;
    audioEl = el;

    ensureAudio().then(function (ctx) {
      if (ctx) {
        try {
          var src = ctx.createMediaElementSource(el);
          analyser = ctx.createAnalyser();
          analyser.fftSize = 256;
          analyser.smoothingTimeConstant = 0.55;
          src.connect(analyser);
          analyser.connect(ctx.destination);
          dataArr = new Uint8Array(analyser.fftSize);
        } catch (e) { analyser = null; }
      }
      if (avatar) { avatar.audioActive = true; avatar.setMode('speaking'); }
      var p = el.play();
      if (p && p.catch) p.catch(function () { fallbackSpeak(); });
    });

    el.onended = function () {
      stopAudio();
      setMode('idle');
      call('speakDone');
    };
    el.onerror = function () { fallbackSpeak(); };
  }

  function fallbackSpeak() {
    var text = lastSpoken;
    stopAudio();
    if (text && call('speakLocal', text) !== null) {
      setMode('speaking');
    } else {
      setMode('idle');
      call('speakDone');
    }
  }

  function readAmplitude() {
    if (!analyser || !dataArr || !avatar) return;
    analyser.getByteTimeDomainData(dataArr);
    var sum = 0;
    for (var i = 0; i < dataArr.length; i++) {
      var v = (dataArr[i] - 128) / 128;
      sum += v * v;
    }
    var rms = Math.sqrt(sum / dataArr.length);
    avatar.setAmplitude(Math.min(1, rms * 7.5));
    requestAnimationFrame(readAmplitude);
  }

  // ═════════════════════════════════════════════════════════ ui

  var avatar = null;

  function setMode(mode) {
    state.mode = mode;
    var mic = $('micBtn');
    mic.classList.remove('listening', 'thinking', 'speaking');
    if (mode === 'listening') mic.classList.add('listening');
    if (mode === 'thinking') mic.classList.add('thinking');
    if (mode === 'speaking') mic.classList.add('speaking');
    $('stopBtn').classList.toggle('hidden', mode !== 'speaking');
    $('typing').classList.toggle('hidden', mode !== 'thinking');

    var map = {
      idle: ['ready', 'Tap mic and say “Neo, YouTube kholo”'],
      listening: ['listening', 'Sun rahi hoon… boliye'],
      thinking: ['thinking', 'Soch rahi hoon…'],
      speaking: ['speaking', 'Bol rahi hoon…']
    };
    var m = map[mode] || map.idle;
    $('heroState').textContent = m[0];
    $('statusText').textContent = m[1];
    if (avatar) avatar.setMode(mode === 'listening' ? 'listening' : mode);
  }

  function addMessage(who, text) {
    var chat = $('chat');
    var wrap = document.createElement('div');
    wrap.className = 'msg ' + who;
    var bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;
    wrap.appendChild(bubble);
    chat.appendChild(wrap);
    chat.scrollTop = chat.scrollHeight;
    while (chat.children.length > 40) chat.removeChild(chat.firstChild);
  }

  function renderPerms(map) {
    var list = $('permList');
    if (!list) return;
    list.innerHTML = '';
    Object.keys(PERM_LABELS).forEach(function (key) {
      var granted = !!(map && map[key]);
      var row = document.createElement('div');
      row.className = 'perm-row';
      var name = document.createElement('span');
      name.textContent = PERM_LABELS[key];
      var st = document.createElement('span');
      st.className = 'state' + (granted ? ' ok' : '');
      st.textContent = granted ? 'allowed' : 'not allowed';
      row.appendChild(name);
      row.appendChild(st);
      list.appendChild(row);
    });
  }

  function refreshPerms() {
    var raw = call('permissionsStatus');
    if (!raw) return;
    try { renderPerms(JSON.parse(raw)); } catch (e) {}
  }

  function applyTheme(theme) {
    state.theme = theme;
    document.body.classList.toggle('light', theme === 'light');
    document.body.classList.toggle('dark', theme !== 'light');
    $('lightTheme').checked = theme === 'light';
  }

  function applyConfig(cfg) {
    if (!cfg) return;
    ['server', 'language', 'theme', 'name'].forEach(function (k) {
      if (typeof cfg[k] === 'string' && cfg[k]) state[k] = cfg[k];
    });
    if (typeof cfg.speak === 'boolean') state.speak = cfg.speak;
    if (typeof cfg.handsFree === 'boolean') state.handsFree = cfg.handsFree;
    if (typeof cfg.automation === 'boolean') state.automation = cfg.automation;
    if (typeof cfg.accessibility === 'boolean') state.accessibility = cfg.accessibility;
    if (typeof cfg.token === 'string') state.token = cfg.token;

    $('serverUrl').value = state.server;
    $('token').value = state.token;
    $('language').value = state.language;
    $('speakReplies').checked = state.speak;
    $('userName').value = state.name;
    $('automation').checked = state.automation;
    $('handsFree').checked = state.handsFree;
    $('greeting').innerHTML = 'Hi, <span>' + escapeHtml(state.name || 'there') + '</span>';
    applyTheme(state.theme);
    $('automationBadge').classList.toggle('hidden', !state.automation);
    updateConnSecurity();
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function saveConfig(partial) {
    call('saveConfig', JSON.stringify(partial));
  }

  function isLocalHost(host) {
    host = (host || '').toLowerCase();
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' ||
      host.endsWith('.local') || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host);
  }

  function inspectUrl(raw) {
    var value = String(raw || '').trim();
    if (!value) return { ok: false, secure: false, msg: 'Backend URL khali hai.' };
    var url;
    try { url = new URL(value); } catch (e) { return { ok: false, secure: false, msg: 'URL format galat hai — http:// ya https:// se shuru karein.' }; }
    if (!/^https?:$/.test(url.protocol)) return { ok: false, secure: false, msg: 'Sirf http:// ya https:// allowed hai.' };
    if (url.protocol === 'https:') {
      return { ok: true, secure: true, msg: 'Secure: HTTPS encrypted · token aur chat text protected.' };
    }
    if (isLocalHost(url.hostname)) {
      return { ok: true, secure: false, local: true, msg: 'Local mode: cleartext sirf is phone/LAN par. Cloud ke liye HTTPS use karein.' };
    }
    return { ok: false, secure: false, msg: 'Cloud URL ke liye https:// zaroori hai. HTTP par token intercept ho sakta hai.' };
  }

  function updateConnSecurity() {
    var box = $('connSecurity');
    var info = inspectUrl($('serverUrl').value);
    box.className = 'conn-lock ' + (info.ok ? (info.secure ? 'secure' : 'local') : 'bad');
    box.textContent = info.ok ? (info.secure ? 'Secure · ' : 'Local · ') + info.msg : info.msg;
  }

  function applyServer(raw, persist) {
    var info = inspectUrl(raw);
    if (!info.ok) {
      toast(info.msg);
      $('serverUrl').value = state.server;
      updateConnSecurity();
      return false;
    }
    state.server = raw.trim().replace(/\/+$/, '');
    $('serverUrl').value = state.server;
    updateConnSecurity();
    if (persist) saveConfig({ server: state.server });
    return true;
  }

  function setBackend(ok) {
    state.online = !!ok;
    var badge = $('netBadge');
    badge.classList.toggle('online', !!ok);
    badge.classList.toggle('offline', !ok);
    $('netText').textContent = ok ? 'online' : 'offline';
    var info = inspectUrl(state.server);
    if (!ok) {
      $('connHint').textContent = info.local
        ? 'Backend offline. Termux me "cd ~/neo-ai/backend && ./run.sh" chalayein, phir token check karein.'
        : 'Cloud backend reachable nahi ya token galat hai. URL, HTTPS aur NEO_TOKEN check karein.';
    } else {
      $('connHint').textContent = 'Connected. AI provider keys sirf backend par hain — APK/WebView me kabhi nahi.';
    }
  }

  // ═════════════════════════════════════════════════ events

  document.addEventListener('DOMContentLoaded', function () {
    try {
      if (window.THREE) avatar = new NeoAvatar($('stage'));
    } catch (e) {
      console.warn('avatar failed', e);
      $('stage').style.display = 'none';
    }

    // settings open/close
    $('settingsBtn').addEventListener('click', function () {
      $('settingsOverlay').classList.remove('hidden');
      refreshPerms();
    });
    $('closeSettings').addEventListener('click', function () {
      $('settingsOverlay').classList.add('hidden');
    });
    $('settingsOverlay').addEventListener('click', function (e) {
      if (e.target === $('settingsOverlay')) $('settingsOverlay').classList.add('hidden');
    });

    // conn
    $('serverUrl').addEventListener('input', updateConnSecurity);
    $('serverUrl').addEventListener('change', function () { applyServer(this.value, true); });
    $('token').addEventListener('change', function () { saveConfig({ token: this.value.trim() }); });
    $('presetLocal').addEventListener('click', function () {
      applyServer('http://127.0.0.1:8787', true);
      toast('Local Termux mode. Backend phone par chalna chahiye.');
    });
    $('presetCloud').addEventListener('click', function () {
      if (/^https:\/\//i.test(state.server)) {
        toast('Cloud mode already set.');
        return;
      }
      $('serverUrl').value = 'https://';
      $('serverUrl').focus();
      updateConnSecurity();
      toast('Apna HTTPS backend URL daalein (Render/Fly/VPS).');
    });
    $('checkServer').addEventListener('click', function () {
      if (!applyServer($('serverUrl').value, true)) return;
      saveConfig({ token: $('token').value.trim() });
      call('checkServer');
      toast('Server check ho raha hai…');
    });
    $('copyToken').addEventListener('click', function () {
      if (call('copy', $('token').value)) toast('Token copy ho gaya.');
      else toast('Copy nahi hua — token manually select karein.');
    });

    // voice + name
    $('language').addEventListener('change', function () { saveConfig({ language: this.value }); });
    $('speakReplies').addEventListener('change', function () { saveConfig({ speak: this.checked }); });
    $('userName').addEventListener('change', function () {
      saveConfig({ name: this.value });
      $('greeting').innerHTML = 'Hi, <span>' + escapeHtml(this.value || 'there') + '</span>';
    });

    // android access
    $('askPerms').addEventListener('click', function () {
      call('requestPermissions', [
        'android.permission.RECORD_AUDIO',
        'android.permission.READ_CONTACTS',
        'android.permission.CALL_PHONE',
        'android.permission.CAMERA',
        'android.permission.READ_CALENDAR',
        'android.permission.WRITE_CALENDAR',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.POST_NOTIFICATIONS'
      ].join(','));
      toast('Permission dialogs aa rahe hain…');
    });
    $('batteryBtn').addEventListener('click', function () { call('openBatterySettings'); });
    $('a11yBtn').addEventListener('click', function () { call('openAccessibilitySettings'); });
    $('automation').addEventListener('change', function () {
      var on = call('setAutomation', this.checked);
      state.automation = !!on;
      $('automationBadge').classList.toggle('hidden', !on);
    });
    $('handsFree').addEventListener('change', function () {
      saveConfig({ handsFree: this.checked });
      call('setHandsFree', this.checked);
    });

    // theme
    $('lightTheme').addEventListener('change', function () {
      applyTheme(this.checked ? 'light' : 'dark');
      saveConfig({ theme: this.checked ? 'light' : 'dark' });
    });

    // chips
    Array.prototype.forEach.call(document.querySelectorAll('.chips button'), function (btn) {
      btn.addEventListener('click', function () {
        var input = $('input');
        input.value = btn.getAttribute('data-cmd') || '';
        input.focus();
      });
    });

    // composer
    $('typebar').addEventListener('submit', function (e) {
      e.preventDefault();
      var input = $('input');
      var text = input.value.trim();
      if (!text) return;
      ensureAudio();
      input.value = '';
      input.blur();
      call('sendText', text);
      setMode('thinking');
    });
    $('keyboardBtn').addEventListener('click', function () { $('input').focus(); });

    // mic
    $('micBtn').addEventListener('click', function () {
      ensureAudio();
      if (state.mode === 'listening') {
        call('stopListening');
        setMode('idle');
      } else {
        if (state.mode === 'speaking') call('stopSpeaking');
        call('startListening');
      }
    });
    $('stopBtn').addEventListener('click', function () {
      call('stopSpeaking');
      stopAudio();
      setMode('idle');
    });

    // wizard
    $('wizardDone').addEventListener('click', finishWizard);
    $('wizardLater').addEventListener('click', finishWizard);
    $('wizardSteps').addEventListener('click', function (e) {
      var btn = e.target.closest('button[data-act]');
      if (!btn) return;
      var act = btn.getAttribute('data-act');
      if (act === 'mic') call('requestPermissions', 'android.permission.RECORD_AUDIO');
      if (act === 'contacts') {
        call('requestPermissions', 'android.permission.READ_CONTACTS,android.permission.CALL_PHONE');
      }
      if (act === 'notif') call('requestPermissions', 'android.permission.POST_NOTIFICATIONS');
      if (act === 'a11y') call('openAccessibilitySettings');
      var li = btn.closest('li');
      if (li) li.classList.add('done');
    });

    // initial state from native
    var raw = call('state');
    if (raw) {
      try { applyConfig(JSON.parse(raw)); } catch (e) {}
    }
    if (!localStorage.getItem('neo_setup_done')) {
      setTimeout(function () { $('wizardOverlay').classList.remove('hidden'); }, 600);
    }
    refreshPerms();
    setMode('idle');
    setInterval(refreshPerms, 4000);
    setInterval(function () { readAudioTick(); }, 60);
  });

  function readAudioTick() {
    if (analyser && dataArr && avatar) {
      analyser.getByteTimeDomainData(dataArr);
      var sum = 0;
      for (var i = 0; i < dataArr.length; i++) {
        var v = (dataArr[i] - 128) / 128;
        sum += v * v;
      }
      var rms = Math.sqrt(sum / dataArr.length);
      avatar.setAmplitude(Math.min(1, rms * 7.5));
    } else if (avatar && state.mode !== 'speaking') {
      avatar.setAmplitude(0);
    }
  }

  function finishWizard() {
    localStorage.setItem('neo_setup_done', '1');
    $('wizardOverlay').classList.add('hidden');
    refreshPerms();
  }

  // ══════════════════════════════════════════════════ native hooks

  window.Neo = {
    onReady: function () {
      var raw = call('state');
      if (raw) { try { applyConfig(JSON.parse(raw)); } catch (e) {} }
      refreshPerms();
      setMode('idle');
    },
    onConfig: function (cfg) {
      applyConfig(cfg);
      state.automation = !!(cfg && cfg.automation);
      $('automationBadge').classList.toggle('hidden', !state.automation);
      refreshPerms();
    },
    onState: function (mode) { setMode(mode || 'idle'); },
    onUser: function (text, fromVoice) { addMessage('user', text); },
    onAssistant: function (text) {
      if (!text) return;
      lastSpoken = text;
      addMessage('neo', text);
    },
    onPartial: function (text) {
      var el = $('partial');
      if (text) { el.textContent = text; el.classList.add('show'); }
      else { el.textContent = ''; el.classList.remove('show'); }
    },
    onError: function (msg) {
      toast(msg);
      setMode('idle');
    },
    onBackend: function (ok) { setBackend(ok); },
    onPermissions: function (map) { renderPerms(map); },
    onSpeakEnd: function () { setMode('idle'); },
    onWeather: function (text) { lastSpoken = text; },
    playAudio: function (b64, mime) { playAudio(b64, mime); },
    stopAudio: function () { stopAudio(); },
    setAmp: function (v) { if (avatar) avatar.setAmplitude(v); }
  };
})();
