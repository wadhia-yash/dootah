# Minimal V2 BasicText example

This is a two-file source example for the [quickstart](../../../docs/getting-started/quickstart.md),
not a separately provisioned app or a backend. Both files declare the same function;
copy only one into your application at a time.

1. Before the native build, copy [baseline/CheckoutScreen.kt](baseline/CheckoutScreen.kt)
   into your application module's `example.dootah` package. Call `CheckoutScreen(100)`
   from your normal native Compose UI on a light background. It displays `Discount: 10`.
2. Integrate Dootah, enrollment and HTTPS delivery, build/retain/install the exact
   APK, then import/register its contract as shown in the quickstart.
3. Change the helper's `10` to `20`, matching [ota/CheckoutScreen.kt](ota/CheckoutScreen.kt).
   Keep imports, path, signature and call site unchanged. Run the quickstart's
   `dootah analyze`, `dootah publish` and `dootah rollout` commands.
4. Without rebuilding/reinstalling, check/download and restart: expect `Discount: 20`.
   Signed rollback should restore `Discount: 10`; also verify offline restart.

The native renderer is unthemed `BasicText`, not Material Text fidelity. The helper
is local, pure and bounded; its input is the existing installed caller's Int. These
files show ordinary Kotlin rather than hand-authored IR or JavaScript. Publication
and device steps require your app's certificate, Cloud binding and enrolled install.
The documentation validator checks the exact files through the current analyzer and
PortableProgram. This is not a claim that this new excerpt was OTA-tested on a phone.
