# Findings

## FolderIntegration (2026-10-30)

- **A queued microflow commits only when it ends.** A single worker looping over all items showed nothing in the pop-up until the whole job finished. `SUB_RunJob` now does one step per call and queues itself again, so every step commits and the pop-up can show the progress. Verified in the running app for all three scenarios, including Retry.
- **The pop-up follows the job with a nanoflow poll** (`NAN_WatchJob`: wait 400 ms with `JSA_Wait`, then `ACT_Job_Sync`, which rolls back the unchanged job and nodes with refresh-in-client).
- **`toString()` on an enumeration returns its caption**, so CSS classes for node states are built with `if` chains, not from `toString`.
- **REST is not called.** `REST_ValidateNames`, `REST_CreateFolder` and `REST_UploadFile` only log and return a `RestResponse` that follows the prototype scenario. Replace their bodies with REST call activities.
- `JA_SimulateLatency` (Java sleep) stands in for network time and is only called from the placeholder flow `SUB_RunJob`; drop it with the placeholders.
- `mxcli run --local` needs PostgreSQL; on this machine use `--db-type hsqldb`.
- `lint` leaves CONV011 (commit in a loop in the retry reset, a handful of rows), CONV008 (one module role mapped to both app roles) and CONV003 (page name). All three are intentional.

## FolderCreator (2026-10-30)

Replaces the earlier FolderIntegration module (task queue, persistent job and node entities) with a minimal synchronous version.

- **Flow:** `NAN_CreateFolders` (nanoflow) gets the ticket from `SUB_GetOTDSTicket`, then calls `SUB_GetOrCreateFolder` per folder; that calls the mock `SUB_GetFolder` and `SUB_CreateFolder`, both of which take the ticket (logged as the `OTCSTICKET` header). No REST call is made; replace the bodies of the two mocks with REST calls.
- **Verified:** 6 microflow tests pass (`mxcli test tests/ --local --db-type hsqldb`); `docker check` 0 errors. In the running app, "All succeed" ticks the six folders one by one and "Fail at Q4" stops at Q4 with Try again. "Some already exist" is covered by a test only, not clicked through.
- **Retrieved lists of non-persistent objects come back newest first**, so folders carry a `Sequence` and `DS_Items` sorts them. A listview over a non-persistent entity cannot use `database from` (CE8812); use a nanoflow data source.
- A `RETURN`/end event inside a loop is CE0068; use `BREAK` and check after the loop.
- Nanoflows are named `NAN_` because lint CONV010 wants `ACT_` flows to delegate everything to `SUB_`. Lint then reports CUSTOM002 (info) for the prefix.
- `demo_user` has a local test password (`mdlsource/03_demo_user.mdl`) so the app can be signed into on this machine; playwright is not installed here, so the browser pane was used instead.
- **Pop-up styling** lives in `themesource/foldercreator/web/main.scss` (`fc-` classes). Status icons (spinner, check, cross) are CSS `::before` on a `fc-ico--<status>` class, so no icon attribute is needed. Atlas pads listview `li` items; the SCSS resets that with `.mx-listview.fc-list > ul > li`.
- **Speed** (Slow 800 ms, Normal 400 ms, Fast 100 ms) is an attribute on `Demo`; `NAN_CreateFolders` passes it to `JSA_Wait`.
