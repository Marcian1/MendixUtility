# Findings

## FolderIntegration (2026-10-30)

- **A queued microflow commits only when it ends.** A single worker looping over all items showed nothing in the pop-up until the whole job finished. `SUB_RunJob` now does one step per call and queues itself again, so every step commits and the pop-up can show the progress. Verified in the running app for all three scenarios, including Retry.
- **The pop-up follows the job with a nanoflow poll** (`NAN_WatchJob`: wait 400 ms with `JSA_Wait`, then `ACT_Job_Sync`, which rolls back the unchanged job and nodes with refresh-in-client).
- **`toString()` on an enumeration returns its caption**, so CSS classes for node states are built with `if` chains, not from `toString`.
- **REST is not called.** `REST_ValidateNames`, `REST_CreateFolder` and `REST_UploadFile` only log and return a `RestResponse` that follows the prototype scenario. Replace their bodies with REST call activities.
- `JA_SimulateLatency` (Java sleep) stands in for network time and is only called from the placeholder flow `SUB_RunJob`; drop it with the placeholders.
- `mxcli run --local` needs PostgreSQL; on this machine use `--db-type hsqldb`.
- `lint` leaves CONV011 (commit in a loop in the retry reset, a handful of rows), CONV008 (one module role mapped to both app roles) and CONV003 (page name). All three are intentional.
