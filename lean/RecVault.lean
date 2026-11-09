/-
  RecVault: machine-checked guarantees for the backup brain.
  Core Lean 4 only (no Mathlib), so it checks in seconds:   lean RecVault.lean

  What is modeled
  * L : recordings on the phone (each has a Key = name+size+hash, and a creation time)
  * R : keys a cloud has VERIFIED (the cloud reported the same name, size AND hash)
  * Steps the app can take: record a new chunk, verify one upload, or fail (network down,
    corrupt upload, Google/Microsoft outage, anything). There is no delete step,
    because the app has no code that deletes.

  Kotlin twin: core/src/main/kotlin/recvault/core/Planner.kt
  (PlannerTest re-checks every theorem below on the real Kotlin code.)

  What this does NOT prove: that Wi-Fi exists, that the phone has battery, or that the
  Kotlin compiler / Android / Google are bug-free. It proves the LOGIC can't lose track
  of a file and can't stay silent while something is unbacked.
-/
namespace RecVault

structure Key where
  name : String
  size : Nat
  hash : String
deriving DecidableEq

structure LFile where
  key  : Key
  time : Nat
deriving DecidableEq

/-- Files on the phone the cloud has not verified. -/
def plan (L : List LFile) (R : List Key) : List LFile :=
  L.filter (fun f => decide (f.key ∉ R))

theorem mem_plan {f : LFile} {L : List LFile} {R : List Key} :
    f ∈ plan L R ↔ f ∈ L ∧ f.key ∉ R := by
  simp [plan]

/-- The plan never invents files. -/
theorem plan_sublist (L : List LFile) (R : List Key) : (plan L R).Sublist L :=
  List.filter_sublist

/-- "Nothing left to upload" means exactly "every recording is verified in the cloud". -/
theorem plan_nil_iff {L : List LFile} {R : List Key} :
    plan L R = [] ↔ ∀ f ∈ L, f.key ∈ R := by
  constructor
  · intro h f hf
    apply Classical.byContradiction
    intro hk
    have : f ∈ plan L R := mem_plan.mpr ⟨hf, hk⟩
    rw [h] at this
    simp at this
  · intro h
    apply List.eq_nil_iff_forall_not_mem.mpr
    intro f hf
    exact (mem_plan.mp hf).2 (h f (mem_plan.mp hf).1)

/-- One full sync pass (every planned file uploaded and verified) leaves nothing behind. -/
theorem after_sync (L : List LFile) (R : List Key) :
    plan L (R ++ (plan L R).map LFile.key) = [] := by
  apply plan_nil_iff.mpr
  intro f hf
  by_cases h : f.key ∈ R
  · exact List.mem_append_left _ h
  · apply List.mem_append_right
    exact List.mem_map.mpr ⟨f, mem_plan.mpr ⟨hf, h⟩, rfl⟩

/-- The overdue alarm: some unverified file is older than T. -/
def alarm (now T : Nat) (L : List LFile) (R : List Key) : Bool :=
  (plan L R).any (fun f => decide (now - f.time > T))

/-- THE NO-SILENT-FAILURE THEOREM: if the alarm is quiet, every recording older than T
    is verified in the cloud. Silence is proof of backup. -/
theorem alarm_silent_sound {now T : Nat} {L : List LFile} {R : List Key}
    (quiet : alarm now T L R = false) :
    ∀ f ∈ L, now - f.time > T → f.key ∈ R := by
  intro f hf hold
  apply Classical.byContradiction
  intro hk
  have hp : f ∈ plan L R := mem_plan.mpr ⟨hf, hk⟩
  have : alarm now T L R = true := by
    unfold alarm
    exact List.any_eq_true.mpr ⟨f, hp, by simpa using hold⟩
  rw [quiet] at this
  exact Bool.false_ne_true this

/-- No false alarms: if it rings, something old really is unverified. -/
theorem alarm_true_real {now T : Nat} {L : List LFile} {R : List Key}
    (ring : alarm now T L R = true) :
    ∃ f ∈ L, now - f.time > T ∧ f.key ∉ R := by
  unfold alarm at ring
  obtain ⟨f, hp, hold⟩ := List.any_eq_true.mp ring
  exact ⟨f, (mem_plan.mp hp).1, by simpa using hold, (mem_plan.mp hp).2⟩

/-! ## The app as a state machine -/

structure St where
  L : List LFile
  R : List Key

inductive Step : St → St → Prop
  /-- a new chunk finished recording -/
  | record   (s : St) (f : LFile) : Step s ⟨s.L ++ [f], s.R⟩
  /-- an upload of a planned file came back with matching name+size+hash -/
  | verified (s : St) (f : LFile) (h : f ∈ plan s.L s.R) : Step s ⟨s.L, s.R ++ [f.key]⟩
  /-- anything went wrong: nothing changes -/
  | fail     (s : St) : Step s s

inductive Reach : St → St → Prop
  | refl (s : St) : Reach s s
  | tail {s t u : St} : Reach s t → Step t u → Reach s u

theorem step_keeps_local {s t : St} (h : Step s t) : ∀ f ∈ s.L, f ∈ t.L := by
  intro f hf
  cases h with
  | record g => exact List.mem_append_left _ hf
  | verified => exact hf
  | fail => exact hf

theorem step_keeps_verified {s t : St} (h : Step s t) : ∀ k ∈ s.R, k ∈ t.R := by
  intro k hk
  cases h with
  | record => exact hk
  | verified => exact List.mem_append_left _ hk
  | fail => exact hk

/-- NEVER LOSE: no sequence of events, including any number of failures, removes a recording. -/
theorem reach_keeps_local {s t : St} (h : Reach s t) : ∀ f ∈ s.L, f ∈ t.L := by
  induction h with
  | refl => exact fun f hf => hf
  | tail _ st ih => exact fun f hf => step_keeps_local st f (ih f hf)

/-- ONCE SAFE, ALWAYS SAFE: a verified recording stays on the phone and stays verified. -/
theorem reach_keeps_verified {s t : St} (h : Reach s t) : ∀ k ∈ s.R, k ∈ t.R := by
  induction h with
  | refl => exact fun k hk => hk
  | tail _ st ih => exact fun k hk => step_keeps_verified st k (ih k hk)

/-- SOUNDNESS: every verified key belongs to a real recording (the cloud can't "verify" a phantom). -/
def Sound (s : St) : Prop := ∀ k ∈ s.R, ∃ f ∈ s.L, f.key = k

theorem step_sound {s t : St} (h : Step s t) (hs : Sound s) : Sound t := by
  cases h with
  | record g =>
    intro k hk
    obtain ⟨f, hf, e⟩ := hs k hk
    exact ⟨f, List.mem_append_left _ hf, e⟩
  | verified g hg =>
    intro k hk
    rcases List.mem_append.mp hk with hk | hk
    · exact hs k hk
    · simp at hk
      exact ⟨g, (mem_plan.mp hg).1, hk.symm⟩
  | fail => exact hs

theorem reach_sound {s t : St} (h : Reach s t) (hs : Sound s) : Sound t := by
  induction h with
  | refl => exact hs
  | tail _ st ih => exact step_sound st ih

/-- PROGRESS: each verified upload strictly shrinks the to-do list, so with the network
    up, at most |plan| successes empty it. Retrying can't loop forever on a done file. -/
theorem verified_progress {L : List LFile} {R : List Key} {f : LFile}
    (hf : f ∈ plan L R) :
    (plan L (R ++ [f.key])).length < (plan L R).length := by
  have hsub : plan L (R ++ [f.key]) = (plan L R).filter (fun g => decide (g.key ≠ f.key)) := by
    unfold plan
    rw [List.filter_filter]
    congr 1
    funext g
    by_cases h1 : g.key ∈ R <;> by_cases h2 : g.key = f.key <;> simp [h1, h2]
  rw [hsub]
  apply List.length_filter_lt_length_iff_exists.mpr
  exact ⟨f, hf, by simp⟩

/-! ## v1.1: the watchdog works from a STALE copy of the cloud's list

The phone only knows the verified set V from its last successful listing; the cloud's
true verified set R can only be bigger (V ⊆ R). These prove that staleness can make the
alarm ring early, but can never make it stay quiet about a real gap. -/

/-- Knowing more never creates an alarm: R ⊆ R' and quiet under R ⇒ quiet under R'. -/
theorem alarm_antitone {now T : Nat} {L : List LFile} {V R : List Key}
    (sub : ∀ k ∈ V, k ∈ R) (ring : alarm now T L R = true) : alarm now T L V = true := by
  obtain ⟨f, hf, hold, hk⟩ := alarm_true_real ring
  unfold alarm
  apply List.any_eq_true.mpr
  exact ⟨f, mem_plan.mpr ⟨hf, fun hv => hk (sub _ hv)⟩, by simpa using hold⟩

/-- STALE QUIET IS STILL TRUE QUIET: if the alarm is silent on the phone's stale list V,
    every recording older than T really is verified in the cloud. -/
theorem stale_quiet_sound {now T : Nat} {L : List LFile} {V R : List Key}
    (sub : ∀ k ∈ V, k ∈ R) (quiet : alarm now T L V = false) :
    ∀ f ∈ L, now - f.time > T → f.key ∈ R :=
  fun f hf hold => sub _ (alarm_silent_sound quiet f hf hold)

/-! ## v1.1: the hash cache can't hide a recording

Bug fixed in v1.1: a pass that read a chunk in the instant it was created saw 0 bytes and
cached "empty"; empty files are skipped, so that recording would never upload and never
alarm. Rule now: cache only when the hash saw bytes AND MediaStore agrees on the size. -/

def cacheable (hashed msSize : Nat) : Bool := decide (0 < hashed) && decide (hashed = msSize)

/-- A cache is a list of (key, size) entries, only ever extended through `cacheable`. -/
inductive CacheOK : List (String × Nat) → Prop
  | nil : CacheOK []
  | skip {c} (h : CacheOK c) : CacheOK c
  | store {c} (k : String) (hashed ms : Nat) (h : CacheOK c) (ok : cacheable hashed ms = true) :
      CacheOK ((k, hashed) :: c)

/-- Every remembered size is nonzero: a cached entry can never mark a recording "empty". -/
theorem cache_never_empty {c : List (String × Nat)} (h : CacheOK c) : ∀ e ∈ c, 0 < e.2 := by
  induction h with
  | nil => intro e he; simp at he
  | skip _ ih => exact ih
  | store k hashed ms _ ok ih =>
    intro e he
    rcases List.mem_cons.mp he with he | he
    · subst he
      simp [cacheable] at ok
      exact ok.1
    · exact ih e he

end RecVault
