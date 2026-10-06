import { sendMessage } from 'expo-brownfield';

// Executed by the hidden host; no React component or RN surface is registered.
sendMessage({
  type: 'dootah.title.v1',
  title: process.env.EXPO_PUBLIC_DOOTAH_TITLE || 'Dootah Native Baseline',
});
