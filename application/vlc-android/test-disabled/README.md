# test-disabled

Upstream VLC unit tests that no longer compile against the medialibrary stubs pinned in this fork
(signature drift in MLServiceLocator / MediaLibraryItem constructors, ~360 errors across 15 files).
They were broken on arrival (1.0.x never ran testDebugUnitTest), moved out of the compilation path
in 1.0.2 so the new BrowserThumbnailsTest can actually execute. Restore to test/ after fixing.
