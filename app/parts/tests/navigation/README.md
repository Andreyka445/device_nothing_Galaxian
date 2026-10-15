# Native Activity navigation host tests

`./run-tests.sh` compiles the **production `PartsActivity.kt`** with small Android,
Compose and screen test doubles. It exercises its real lifecycle methods, route
validation, toolbar callbacks, notification forwarding, saved-state restoration,
owner checks and rapid-tap handling. It does not copy the navigation implementation.

The doubles record Activity launches and screen inputs; they do not simulate
WindowManager, task selection, the back gesture, composition or Compose's
saveable-state registry. In particular, saving the unconsumed review input is
covered here; the popup's own saved state belongs to `AdaptiveScreen`.

Device checks still required:

- Enter each page from Home, use toolbar Back, and repeat with three-button Back.
- Drag predictive Back from both edges, cancel halfway, then drag again and commit.
- Repeat in RTL and with light/dark themes; check the window background and corners.
- Rotate Home, a scrolled page and Adaptive with an open review dialog. Confirm
  Adaptive's selected tab and both lists keep their positions.
- Tap monitoring/proposal notifications with the app closed and while Home, GPU or
  Adaptive is open. Confirm the exact proposal is reviewed and Back reaches Home.
- Recreate the process while reviewing: expired/nonexistent proposal IDs must remain
  unavailable, and opening a notification must never approve a change.

Notifications may select a separate Parts task when Parts was originally opened
inside Settings. `CLEAR_TOP | SINGLE_TOP` deduplicates within the selected task;
these tests do not claim global task deduplication or physical animation validation.
