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

## FlowScope spike (2026-10-30, Mendix 11.12.5, HSQLDB)

A throwaway Java action logged raw log lines and `IProfiler` calls while microflows ran (removed again). What it showed:

- **Join key:** the `[guid]` prefix of the `MicroflowEngine` lines is the same GUID that `IProfiler.enterDatabase` gets as its 2nd argument. So log lines and SQL calls of one request can be matched exactly.
- **Log format:** `Starting/Finished execution of microflow 'X'` at DEBUG; `Executing activity: {"current_activity":{"caption":..,"type":..},"name":..}` at TRACE. A sub-microflow call is an activity of type `SubMicroflow`; a loop body runs as a pseudo-microflow `X.nested.<id>` (skip it, as NanoProfiler does).
- **SQL arrives with `?` placeholders**, never values (`UPDATE "flowscope$execution" SET "durationms" = ? WHERE "id" = ?`). Only whitespace and `IN (?,?,..)` lists need normalising.
- **`enterDatabase(sessionId, executionGuid, sql, startEpochMs)` and `finishDatabase(handle, endEpochMs)` work**, and the handle returned by `enterDatabase` comes back. They run on `ProfileUtil-N` threads, not the request thread, so key everything by the execution GUID. `finishDatabase` can arrive with a `null` handle (call began before registration) and must be tolerated.
- **`enterRuntime`/`finishRuntime` were never called** — not usable.
- **Log subscribers run on one logging thread (`Thread-0`)**, asynchronously: lag was 0 ms in steady state but ~600-700 ms during the startup burst. Duration must therefore come from `LogMessage.timestamp` (ms), never from the time the subscriber sees the message.
- `LogMessage` also carries `traceId`/`spanId`.
- `mxcli run --local` defaults to PostgreSQL; with no server installed use `--db-type hsqldb`.

## FlowScope module (2026-10-30)

Persists slow microflow runs and shows them in an expandable list (`FlowScope.Executions`). Capture is Java (`javasource/flowscope/implementation`), the rest is MDL (`mdlsource/04`-`09`).

- **How capture works:** the `MicroflowEngine` log (TRACE, subscribed in code) gives microflow start/finish and activities with their timestamps; `IProfiler.enterDatabase/finishDatabase` gives each statement and its duration; both carry the request's execution GUID, which joins them. Only the entry-point microflow is judged against the threshold; its sub-flows are kept with it. Finished runs go to a queue and a writer thread saves them, so the profiled request never waits for the database.
- **Verified in the running app (HSQLDB, 11.12.5):** a deliberately slow microflow (1.9 s, two sub-flow calls, a while loop with 8 retrieves) was captured with the right totals (11 selects), the sub-flows at 0.3 s each, the loop at 1.35 s flagged as the hot step, and statements with `?` placeholders and per-statement time. Retention cleanup (`SUB_DeleteOldExecutions`) is scheduled nightly but was **not** run against old data; the runs in the app were minutes old.
- **Privacy:** the runtime already hands over SQL with `?`; `SqlText` also replaces any string or number literal and collapses `IN (?,?,..)`. No statement text with values is stored. Checked with a unit-style run (`'secret'` never appears).
- **Changing the `MicroflowEngine` log level at runtime (admin API, cloud portal, `mxcli log set`) replaces our subscription**, so capture silently stops. A 15 s check puts it back to TRACE and logs a warning. Verified: set INFO, the warning appeared within 15 s, and the next run was captured. NanoProfiler has the same weakness and no check.
- **Only one profiler can be registered.** If NanoProfiler is started with `UseIProfiler`, FlowScope logs a warning and still captures runs, without statements.
- `IProfiler.enterRuntime/finishRuntime` are never called on 11.12.5 (see spike section); not used.
- **Gallery text filter** rendered "Unable to get filter store" when written through MDL, so the page uses a List view with its built-in search (`SEARCH BY`). Sorting is fixed (slowest first).
- **Page state** (`IsExpanded`) is an attribute on `Execution` changed in a nanoflow and never committed; the list does not refresh after actions such as running the sample flow until you press refresh in the search bar.
- **Text widgets only show attributes**, so durations are stored as seconds (Decimal) rather than computed.
- `mxcli run --local` needs `--db-type hsqldb` here (no PostgreSQL). `mxcli check`'s `RETRIEVE ... LIMIT 1` returns a list; use `FIRST` for one object.
- **Lint leaves** CONV006 (create/delete granted: the demo-data and delete-all microflows run as the developer), CONV007 (single developer role sees all rows, by design), CONV002 (defaults), CONV016 (event handler on Settings applies a saved change at once) and CUSTOM002 (`ASU_` prefix). Report score 82/100.
- `demo_user` is the prototype's user; `flowscope_dev` (Administrator role, which holds `FlowScope.Developer`) is the local test login in `mdlsource/08_flowscope_demo_user.mdl`.
- `mdlsource/07_flowscope_navigation.mdl` owns the whole Responsive menu; keep it in step with `02_foldercreator_pages.mdl`.

### FlowScope sample flow and demo data (2026-10-30)

- **Demo data is gone** (button, `ACT_CreateDemoData`, `SUB_AddDemoExecution`). Use `FlowScope.SAMPLE_RunFlow` (button "Run sample flow", `mdlsource/05_flowscope_sample.mdl`) instead: it builds and deletes `FlowScope.SampleItem` rows and runs about 4 s of sub-flows, so the page shows real captured data.
- **Verified:** one run was captured with 49 steps to depth 4: create list, while loop, commit, four kinds of retrieve, count/sum/average, filter, sort, head, find, a loop with 40 selects (1.9 s, flagged hot), a loop with 30 updates, a caught division error (decision took the error branch), a 1.4 s call two levels deep, and the delete. The Queries tab showed the same select 41 times (the N+1) and 30 identical updates.
- **Known gap: the 40 deletes of the cleanup step are not counted** (no DELETE statement among the captured statements). Not investigated; likely issued outside the window or request id the engine watches.
- **List view page size** is 20 by default, which cut a 49-step breakdown to 20 rows; steps now use 500 and queries 200 (matches `RunBuilder.MAX_STEPS`/`MAX_QUERIES`).
- **Default exclude pattern is now `^NanoProfiler\.`** (it was `^(FlowScope|NanoProfiler)\.`), so FlowScope's own flows, the sample included, can be captured. The old default would have hidden the sample.
- The sample's entry flow is `SAMPLE_`, not `ACT_`, because lint CONV010 wants `ACT_` flows to delegate to `SUB_` and the sample shows every activity type on purpose. Report score 78/100 (was 82 before the sample).
- The Save button on `Settings_Popup` is `save changes close page` (set in the app, now in `06_flowscope_pages.mdl`).
- Browser-pane clicks by element reference can land off target; clicking by screenshot coordinates or repeating the click works.

### "The sample shows nothing" (2026-10-30)

- **Cause:** on a new database FlowScope starts with capture **off** (logged as "FlowScope capture is off"), and nothing on the page said so, so a run of the sample was never recorded. A database made before the exclude-pattern default changed also kept `^(FlowScope|NanoProfiler)\.`, which hides every FlowScope flow, the sample included.
- **Fix:** a status bar above the list (`DS_Settings` data source). Off: an orange "Capture is off, so nothing is being recorded" with a "Turn on capture" button (`ACT_EnableCapture` -> `SUB_EnableCapture`; the commit fires the after-commit event). On: "Capturing microflows slower than N ms, keeping D days". `ASU_FlowScope` moves the old exclude default to `^NanoProfiler\.` at startup; guarded so an empty pattern cannot break startup.
- **Verified on an empty database** (`--db-name freshcheck`, since removed): banner shown, one click turned capture on, `SAMPLE_RunFlow` ran and `FlowScope.SAMPLE_RunFlow` (3.97 s) was listed after pressing refresh. Setting the old pattern through the pop-up and restarting put `^NanoProfiler\.` back (read with `mxcli oql`). The Settings pop-up has no demo button and Save closes it.
- A shape change to a stored microflow (a new `RETURN`, a new decision) cannot be spliced by `exec`; drop and create it in one run, once, then the script re-runs unchanged.
- Report score 77/100.
