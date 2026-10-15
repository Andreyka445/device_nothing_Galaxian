This is a synthetic, format-faithful fixture, not a captured device measurement.
It mirrors `frameworks/native/services/surfaceflinger/Scheduler/FrameTimeline.cpp`:
`dumpTable`, `SurfaceFrame::dump`, `DisplayFrame::dump` and `FrameTimeline::dumpAll`.
Times are milliseconds relative to the oldest retained frame. Native classification
clears actual-present time for dropped buffers; their explicit Drop time remains.
The 30 fps surface on a 120 Hz display deliberately has different app/display periods.
No production package name, PID, layer name or trace has been copied into the fixture.
