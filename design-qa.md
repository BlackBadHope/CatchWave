# CatchWave — common interface, corrected desktop layout

**Final result: passed for the inspected native layout states. Physical acceptance of Host 0.1.32 and USB audio is pending.**

Visual target: [selected concept 2](docs/ui/concept2.png). [Joint comparison](docs/ui/comparison.png) contains that source, actual Android 49 screens, and real WinForms controls rendered with an inert public fixture. The desktop adapts the same palette, illustration, typography hierarchy and explicit control actions to a wide window.

## Corrections after owner screenshots

The earlier desktop QA missed a real error: borderless preview rendering changed the client size, so it did not represent the running window. In the actual Host, privacy text was clipped, panels collided in one table cell, button corners painted incorrectly, and maximizing stretched the primary button across the screen. The earlier pass did not establish a correct running desktop layout.

Host 0.1.32 keeps normal window borders during preview, draws its true client controls, reserves QR/privacy space, uses one overlay container, clears rounded-button corners, groups content into two native panels, and limits content width/height in a larger window. PC permission is shown separately from Android readiness. Network choice uses the common button style and an accessible native menu.

| State | Evidence |
| --- | --- |
| Waiting / pairing | [Native render](docs/ui/windows-waiting-fixture.png); QR is an inert public fixture. Privacy text and bottom actions fit. |
| Ready / commands | [Native render](docs/ui/windows-ready.png); four tap actions, one permission action, separate phone status. |
| Maximized | [1920 x 1008 client render](docs/ui/windows-maximized-fixture.png); readable content width, stable grouping and button size. |
| Diagnostics / disconnected | Native rendering and layout assertions; no overlapping content panels. |
| Android navigation / PC audio | Actual phone captures in the joint comparison; scan and disconnect stay together at the top. |

Layout assertions cover client sizes 980 x 730, 1000 x 730, 1100 x 760 and 1920 x 1008 on the tested Windows display configuration. Font scaling on other displays remains unverified. No P0/P1/P2 clipping or overlap remained in inspected captures. Native menu styling and exact typography are P3 polish, subject to owner review.

The owner's running Host was intentionally left at 0.1.31 during the Wi-Fi session. The corrected build is prepared separately; rendering is not a claim that it has been activated in that session. Hearing, long screen-off stability, cable reconnection and gesture false-positive rates remain separate acceptance work.
