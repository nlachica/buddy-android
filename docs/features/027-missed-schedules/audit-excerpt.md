### 4. Scheduled jobs are dropped when any other chat works for more than an hour
- **Evidence:** `Schedules.java:39-40`: "another chat at work ... make it wait, for an hour at most";
  `Schedules.java:444-446`: `busy()` is true when `hub.runningCount() > 0`. On Nov 17 the watcher missed every
  slot from 07:00 to 10:00 and first ran at 10:29, while pipeline sessions (`bf-020`, `bf-023`,
  `buddy-fork-022`) logged over 1,000 events an hour. The morning brief went out at 10:31 instead of 07:30
  (`wa-sync/data/brief-date` written Nov 17 17:31 UTC). The curator's 08:00 run did not happen at all that day
  (no prompt in its transcript between 10:32 UTC Nov 17 and 03:01 UTC Nov 18). Runs made: 25 of 34 on Nov 16,
  23 of 34 on Nov 17.
- **Impact:** the "autonomous" jobs quietly stop on exactly the days you work with Buddy the most.
- **Effort:** M (a fork change, through the pipeline).
- **Fix:** in `Schedules.busy()`, don't wait for other chats when the scheduled chat doesn't need the screen
  (the file's own comment says Linux tools and the background browser work without it). Until then the
  `no-long-foreground` hook helps, because chats finish their own turn quickly.

### 5. The release gate is not active in the chats that make releases
