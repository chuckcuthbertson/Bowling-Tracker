# BowlingTracker (V1 prototype)

This is a local, post-shot analysis prototype:
- Record a shot (CameraX video)
- Tap 4 lane corners to calibrate
- Choose breakpoint distance (ft)
- App estimates:
  - board at arrows (15 ft)
  - board at breakpoint
  - speed (mph)

## Quick start (GitHub Actions build)
1. Create a new GitHub repo and upload this project (all files).
2. Go to **Actions** tab → enable workflows if prompted.
3. Click the workflow **Build Debug APK** → Run workflow.
4. After it finishes, download the artifact **app-debug-apk**.
5. On your Android phone, install the APK (you may need to allow "Install unknown apps").

## Notes
- Works best with a tripod and steady framing behind the bowler (~15–25°).
- V1 uses motion-based tracking; lighting/glare may affect results.


If a GitHub build fails, download the **build-log** artifact and share the last lines.


Build fix: uses CameraX 1.4.0 to remain compatible with compileSdk 34.
