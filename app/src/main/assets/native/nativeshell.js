const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    "multiserver",
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());
const codecCaps = JSON.parse(window.NativeInterface.getCodecCapabilities());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    openShortsFeed() {
        window.NativeInterface.openShortsFeed();
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    selectServer() {
        window.NativeInterface.openServerSelection();
    },

    getPlugins() {
        return plugins;
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    return profileBuilder();
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        return "mobile";
    },
    supports(command) {
        command = command.toLowerCase();
        if (command === "chromecast") {
            return window.NativeInterface.hasChromecast();
        }
        return features.includes(command);
    },
    getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};

const SHORTS_BUTTON_ID = 'native-shorts-btn';

// Jellyfin web renders every route through React. Depending on the route (home, dashboard,
// after switching servers, ...) a different header container may be present, and React can
// re-create header nodes at any time. To keep the entry point reliable we anchor the button to
// `document.body` with a fixed position so it survives all navigation and re-renders.
function createShortsButton() {
    if (document.getElementById(SHORTS_BUTTON_ID)) {
        return null;
    }

    const btn = document.createElement('button');
    btn.id = SHORTS_BUTTON_ID;
    btn.type = 'button';
    btn.title = '短视频';
    // Pin to the top-right of the viewport, just below the Jellyfin header bar so it does not
    // overlap the app's own header controls (search / cast / user menu).
    btn.style.cssText = [
        'position:fixed',
        'top:calc(env(safe-area-inset-top, 0px) + 52px)',
        'right:8px',
        'z-index:2147483647',
        'padding:4px 8px',
        'margin:0',
        'border:none',
        'background:transparent',
        'cursor:pointer',
        'pointer-events:auto'
    ].join(';');
    btn.innerHTML = '<span style="font-size:14px;font-weight:bold;color:#00a4dc;background:rgba(0,164,220,0.15);padding:4px 8px;border-radius:12px;">短视频</span>';
    btn.onclick = (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (window.NativeShell && typeof window.NativeShell.openShortsFeed === 'function') {
            window.NativeShell.openShortsFeed();
        }
    };
    return btn;
}

function injectShortsButton() {
    // Already present and still attached -> nothing to do.
    const existing = document.getElementById(SHORTS_BUTTON_ID);
    if (existing && document.body && document.body.contains(existing)) {
        return;
    }

    if (!document.body) {
        return;
    }

    if (existing && existing.parentNode) {
        existing.parentNode.removeChild(existing);
    }

    const btn = createShortsButton();
    if (btn) {
        document.body.appendChild(btn);
    }
}

let shortsObserver = null;

function startShortsButtonInjection() {
    injectShortsButton();

    // Re-inject whenever the DOM changes (React re-renders / route changes).
    if (!shortsObserver && typeof MutationObserver !== 'undefined') {
        shortsObserver = new MutationObserver(() => injectShortsButton());
        shortsObserver.observe(document.body || document.documentElement, {
            childList: true,
            subtree: true
        });
    }

    // Safety net for cases where the header is swapped without a mutation we observe.
    setInterval(injectShortsButton, 2000);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startShortsButtonInjection);
} else {
    startShortsButtonInjection();
}


