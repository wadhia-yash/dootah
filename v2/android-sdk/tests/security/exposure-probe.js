// Diagnostic adversarial OTA, never a portable-logic acceptance artifact.
// Uses only a loopback endpoint and its own temporary cache file. No user data is read.
import { NativeModules, TurboModuleRegistry } from 'react-native';
import { requireOptionalNativeModule } from 'expo-modules-core';

const emit = (name, value) => console.log('DOOTAH_EXPOSURE ' + JSON.stringify({ name, value }));
async function check(name, action) {
  try { emit(name, { ok: true, result: await action() }); }
  catch (error) { emit(name, { ok: false, error: String(error).slice(0, 240) }); }
}

async function probe() {
  emit('globalNames', Object.getOwnPropertyNames(globalThis).sort());
  for (const name of ['nativeModuleProxy', '__turboModuleProxy', 'expo', '__r', '__d',
    'require', 'fetch', 'XMLHttpRequest', 'WebSocket', 'setTimeout', 'setInterval',
    'queueMicrotask', 'eval', 'Function', 'HermesInternal', 'RN$Bridgeless',
    'nativeCallSyncHook', '__fbBatchedBridge', 'localStorage', 'indexedDB',
    'Context', 'Activity', 'Java', 'Packages']) {
    await check('global.' + name, () => {
      const d = Object.getOwnPropertyDescriptor(globalThis, name);
      return { type: typeof globalThis[name], configurable: d?.configurable,
        writable: d?.writable, getter: typeof d?.get };
    });
  }
  for (const name of ['PlatformConstants', 'DeviceInfo', 'AppState', 'Appearance',
    'Networking', 'WebSocketModule', 'BlobModule', 'FileReaderModule', 'Clipboard',
    'IntentAndroid', 'PermissionsAndroid', 'ShareModule', 'ToastAndroid', 'Vibration',
    'ExpoModulesCore', 'AsyncSQLiteDBStorage', 'CameraModule', 'LocationModule',
    'BluetoothModule', 'Contacts', 'DootahUnknownOperation']) {
    await check('native.' + name, () => ({
      nativeModules: NativeModules[name] != null,
      turboRegistry: TurboModuleRegistry.get(name) != null,
      directGlobal: globalThis.nativeModuleProxy?.[name] != null,
    }));
  }
  for (const name of ['ExpoFetchModule', 'ExponentFileSystem', 'FileSystem',
    'ExponentConstants', 'ExpoUpdates', 'ExpoBrownfieldModule', 'ExpoBrownfieldStateModule',
    'ExpoAsset', 'ExpoFontLoader', 'ExpoFontUtils', 'ExpoKeepAwake', 'ExpoDomWebViewModule',
    'EASClient', 'ExpoCamera', 'ExpoLocation', 'ExpoContacts', 'DootahUnknownCapability']) {
    await check('expo.' + name, () => {
      const m = requireOptionalNativeModule(name);
      return { available: m != null, directGlobal: globalThis.expo?.modules?.[name] != null,
        keys: m ? Object.getOwnPropertyNames(m).sort() : [] };
    });
  }
  await check('dynamic.eval', () => (0, eval)('1 + 2') === 3);
  await check('dynamic.function', () => new Function('return 1 + 2')() === 3);
  await check('dynamic.constructorNative', () =>
    (() => {}).constructor('return globalThis.nativeModuleProxy.Networking != null')());
  await check('dynamic.import', async () =>
    (await import('react-native')).TurboModuleRegistry.get('Networking') != null);
  await check('module.metroRequire', () => typeof globalThis.__r === 'function');
  await check('nondeterminism', () => ({ clock: typeof Date.now() === 'number',
    random: typeof Math.random() === 'number', performance: typeof performance.now() === 'number' }));
  await check('async.timer', () => new Promise(resolve => setTimeout(() => resolve(true), 1)));
  await check('network.loopback', async () => {
    const response = await fetch('http://127.0.0.1:3101/dootah-exposure');
    return response.status === 200 && await response.text() === 'dootah-exposure-ok';
  });
  await check('filesystem.cacheRoundTrip', async () => {
    const fs = requireOptionalNativeModule('ExponentFileSystem');
    const path = fs.cacheDirectory + 'dootah-phase6-exposure-marker.txt';
    try {
      await fs.writeAsStringAsync(path, 'dootah-exposure-marker', {});
      return await fs.readAsStringAsync(path, {}) === 'dootah-exposure-marker';
    } finally {
      await fs.deleteAsync(path, { idempotent: true });
    }
  });
  // Deliberately omit the render envelope. Native fallback cannot undo the above calls.
  emit('complete', true);
}
probe().catch(error => emit('fatal', String(error)));
