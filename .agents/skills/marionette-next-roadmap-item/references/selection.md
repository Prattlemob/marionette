# Select the next eligible milestone

Use the live `ROADMAP.md`, its prose ordering overrides, prerequisites, and
verification records together. Do not select by unchecked-box regex alone.
Treat a roadmap **milestone** as an item; its checkboxes are acceptance criteria,
not independent jobs unless the user explicitly requests a smaller scope.

1. Honor a requested milestone, start/end range, or core-only scope. Otherwise
   choose the next unfinished implementation milestone in the documented order.
   A coordinator asked for the entire roadmap includes optional phases; optional
   features retain their documented off-by-default and dependency boundaries.
2. Classify prior milestones from evidence: complete; implemented with outstanding
   acceptance; incomplete implementation; or blocked on a named decision or
   prerequisite. Checked boxes do not override contradictory current evidence.
3. Apply ordering overrides in the roadmap before numeric order. For example,
   Phase 4 currently orders M4.1 → M4.6 → M4.2 → M4.3 → M4.4 → M4.5.
   Re-read this text each time; this example is not a permanent schedule.
4. Earlier manual verification debt stays visible. Where later work has already
   been accepted and the next milestone's own prerequisites are met, continue
   that implementation sequence without falsely completing the older milestone.
   Do not skip an unimplemented prerequisite or bypass a milestone that explicitly
   requires the pending acceptance. If an earlier failed check exposes a defect
   in a dependency, fix it before building on it.
5. When the next item hits a genuine decision or acceptance gate, do useful work
   that does not depend on the answer. Ask a precise question through the active
   user/coordinator channel. Do not repeatedly dispatch the same blocked item,
   silently reorder dependent work, or mark the whole roadmap complete.
6. Completion requires both implemented scope and the documented acceptance.
   Reconcile unchecked and unverified work before declaring the full run done.

Examples against the October 2026 baseline:

- M4.1 is verified; M3.3 has manual animation/physical alt-tab debt; M3.4 is
  implemented. The next implementation is M4.6, whose prerequisites are M4.1
  and M2.3. Carry the M3.3 debt in the handoff.
- After M4.6, select M4.2, not M5.1 or the next heading after M4.6.
- M5.1 explicitly requires Phase 3 complete: reconcile remaining M3.3 acceptance
  before treating that prerequisite as satisfied.
- M9.2 cannot publish under an unconfirmed provisional license. Preserve D12's
  decision gate even when the requested scope is the entire roadmap.

Treat these as selection examples, not cached knowledge of today's completion.
