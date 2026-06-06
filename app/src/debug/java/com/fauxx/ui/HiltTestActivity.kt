package com.fauxx.ui

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-enabled host activity for the Compose instrumented screen tests.
 *
 * compose-bom 2026.05.01 tightened `createAndroidComposeRule` so it throws
 * "activity has already set content" when the launched activity calls `setContent()` itself
 * (which [MainActivity] does in its `onCreate`). The screen tests need a Hilt-aware
 * (`@AndroidEntryPoint`) [ComponentActivity] that does NOT set its own content, so the test rule's
 * `setContent {}` is the only content while `hiltViewModel()` inside the screen still resolves.
 *
 * Lives in the `debug` source set (excluded from release builds) and is declared in
 * `src/debug/AndroidManifest.xml`.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
