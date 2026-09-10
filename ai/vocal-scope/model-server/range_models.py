from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class BufferedMetrics(BaseModel):
    referenceRmsCents: float | None = Field(default=None, ge=0)
    model_config = ConfigDict(allow_inf_nan=False)
    creditedHoldSeconds: float | None = Field(default=None, ge=0, le=2.500001)
    continuousHoldSeconds: float = Field(default=0, ge=0, le=2.500001)
    bufferSeconds: float | None = Field(default=None, ge=0, le=2.500001)
    outsideSeconds: float | None = Field(default=None, ge=0, le=2.500001)
    excursionPenalty: float | None = Field(default=None, ge=0, le=20.000001)
    creditedMidi: int | None = Field(default=None, ge=36, le=84)


class PitchCandidate(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    t: float = Field(ge=0)
    initialF0: float = Field(ge=0)
    initialClarity: float = Field(ge=0, le=1)
    correctedF0: float = Field(ge=0)
    correctedClarity: float = Field(ge=0, le=1)
    selectedF0: float = Field(ge=0)
    reason: Literal['initial_continuity','suppressed','corrected_no_history','corrected']


class Attempt(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    attemptId: str = Field(min_length=1, max_length=100)
    targetMidi: int = Field(ge=36, le=84)
    direction: Literal["anchor", "central", "down", "up", "reconfirm", "extension"]
    cueStart: float = Field(ge=0)
    cueEnd: float = Field(ge=0)
    responseStart: float = Field(ge=0)
    responseEnd: float = Field(ge=0)
    evaluationStart: float | None = None
    evaluationEnd: float | None = None
    clientStatus: Literal["passed", "not_passed", "insufficient_quality", "interrupted", "unmeasured"]
    retryOf: str | None = None
    selfReportedRegister: Literal["unknown", "modal", "falsetto"] = "unknown"

    visitId: str | None = Field(default=None, max_length=100)
    advanceReason: Literal["threshold", "manual", "replay", "user_stopped", "app_interrupted", "input_quality", "completed", "anchor_reset"] | None = None
    progressQualified: bool = False
    selectedForVisit: bool = True
    progressEvaluationStart: float | None = Field(default=None, ge=0)
    progressEvaluationEnd: float | None = Field(default=None, ge=0)
    searchOutcome: Literal["reached", "manual_failed"] | None = None
    referenceStart: float | None = Field(default=None, ge=0)
    referenceEnd: float | None = Field(default=None, ge=0)
    referenceMidi: float | None = None
    runEndReason: Literal['voice_ended', 'buffer_timeout', 'outside_timeout', 'max_duration',
        'input_uncertain', 'clipping', 'threshold', 'manual', 'replay', 'user_stopped',
        'app_interrupted', 'input_quality', 'completed', 'anchor_reset'] | None = None
    metrics: BufferedMetrics | None = None
    pitchCandidates: list[PitchCandidate] = Field(default_factory=list, max_length=300)
    probeStage: Literal['anchor', 'coarse', 'bisect', 'refinement'] | None = None
    anchorMidi: int | None = Field(default=None, ge=36, le=84)

    @model_validator(mode="after")
    def timeline(self):
        if (self.progressEvaluationStart is None) != (self.progressEvaluationEnd is None):
            raise ValueError("both progress evaluation bounds are required")
        if self.progressEvaluationStart is not None:
            if (self.progressEvaluationStart < self.responseStart + .2 - .03 or
                self.progressEvaluationEnd > self.responseEnd + .001 or
                abs(self.progressEvaluationEnd - self.progressEvaluationStart - 2) > .001):
                raise ValueError("progress must use a settled two-second response window")
        empty = self.clientStatus == "unmeasured" and self.evaluationStart is None and self.evaluationEnd is None and self.cueEnd == self.responseStart == self.responseEnd
        if empty and self.cueStart <= self.cueEnd:
            return self
        if not (self.cueStart <= self.cueEnd <= self.responseStart < self.responseEnd):
            raise ValueError("invalid attempt timeline")
        if self.responseStart < self.cueEnd + .25 - .001:
            raise ValueError("cue guard is required")
        if (self.evaluationStart is None) != (self.evaluationEnd is None):
            raise ValueError("both evaluation bounds are required")
        if self.evaluationStart is not None:
            if not (self.responseStart + .2 - .03 <= self.evaluationStart < self.evaluationEnd <= self.responseEnd + .001):
                raise ValueError("evaluation must be inside the settled response")
            if not 0 < self.evaluationEnd - self.evaluationStart <= 2.501:
                raise ValueError("invalid evaluation length")
        if self.clientStatus == "passed" and self.evaluationStart is None:
            raise ValueError("a client pass requires its exact evaluation window")
        return self


class RegisterEvent(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    t: float = Field(ge=0)
    register_label: Literal["unknown", "modal", "falsetto"] = Field(alias="register")


class Sampling(BaseModel):
    basicCompleted: bool = False
    refinementStatus: Literal["not_started", "in_progress", "completed"] = "not_started"
    skippedCentralIntervals: list[list[int]] = Field(default_factory=list, max_length=2)
    upperLastSuccess: int | None = Field(default=None, ge=36, le=84)
    lowerLastSuccess: int | None = Field(default=None, ge=36, le=84)
    upperEnd: int | None = Field(default=None, ge=36, le=84)
    lowerEnd: int | None = Field(default=None, ge=36, le=84)
    measuredNoteCount: int = Field(default=0, ge=0, le=49)
    strategy: Literal['adaptive_8', 'adaptive_6'] | None = None
    coarseStep: Literal[8, 6] | None = None
    upperFirstFailure: int | None = Field(default=None, ge=36, le=84)
    lowerFirstFailure: int | None = Field(default=None, ge=36, le=84)
    upperBoundaryPair: list[int] | None = None
    lowerBoundaryPair: list[int] | None = None

    @model_validator(mode="after")
    def intervals(self):
        if self.strategy is not None and self.coarseStep != {'adaptive_8': 8, 'adaptive_6': 6}[self.strategy]:
            raise ValueError('sampling strategy and step must match')
        if any(len(i) != 2 or not 36 <= i[0] <= i[1] <= 84 for i in self.skippedCentralIntervals):
            raise ValueError("invalid skipped central interval")
        for pair, direction, first in ((self.upperBoundaryPair, 1, self.upperFirstFailure),
                                       (self.lowerBoundaryPair, -1, self.lowerFirstFailure)):
            if pair is not None and (len(pair) != 2 or any(not 36 <= m <= 84 for m in pair)
                    or not 1 <= direction*(pair[1]-pair[0]) <= (self.coarseStep or 8) or first is None
                    or direction*(first-pair[1]) < 0):
                raise ValueError('invalid adaptive boundary pair')
        return self


class Submission(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    sessionId: str = Field(min_length=1, max_length=64)
    measurementType: Literal["guided_range"]
    protocolVersion: Literal["guided-range-v1", "guided-range-v2", "guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7"]
    mode: Literal["basic", "extension"] = "basic"
    parentAnalysisId: str | None = None
    startMidi: int | None = Field(default=None, ge=36, le=84)
    minMidi: Literal[36] = 36
    maxMidi: Literal[84] = 84
    noiseRms: float = Field(ge=0, le=1)
    durationSeconds: float = Field(gt=0)
    stopReason: Literal["completed", "three_failures", "user_stopped", "time_limit", "model_limit", "input_quality", "anchor_not_matched", "app_interrupted"]
    lowerBoundaryStatus: str = Field(max_length=40)
    upperBoundaryStatus: str = Field(max_length=40)
    attempts: list[Attempt] = Field()
    registerEvents: list[RegisterEvent] = Field(default_factory=list)
    keepOriginal: bool = False
    sampling: Sampling | None = None

    @model_validator(mode="after")
    def timeline(self):
        legacy = self.protocolVersion == "guided-range-v1"
        adaptive = self.protocolVersion in ('guided-range-v6', 'guided-range-v7')
        strategies = ('adaptive_8', 'adaptive_6') if self.protocolVersion == 'guided-range-v7' else ('adaptive_8',)
        if adaptive and (self.sampling is None or self.sampling.strategy not in strategies):
            raise ValueError('adaptive sampling metadata is required')
        if self.sampling and self.sampling.strategy == 'adaptive_6' and self.protocolVersion != 'guided-range-v7':
            raise ValueError('six-semitone adaptive sampling requires v7')
        if self.mode == "extension" and not self.parentAnalysisId:
            raise ValueError("extension needs a parent")
        if legacy and (self.durationSeconds > (30.1 if self.mode == "extension" else 90.1) or len(self.attempts) > 100 or len(self.registerEvents) > 100):
            raise ValueError("v1 recording exceeds time limit")
        if self.mode == "basic" and self.parentAnalysisId is not None:
            raise ValueError("basic session cannot have a parent")
        ids = set()
        end = 0
        visits = {}
        closed_visits = set()
        active_visit = None
        for attempt in self.attempts:
            if adaptive:
                if attempt.anchorMidi is None or attempt.probeStage is None or attempt.direction == 'central':
                    raise ValueError('v6 requires per-attempt anchor and sampling stage')
                expected_stages = {'anchor': ('anchor',), 'up': ('coarse','bisect'), 'down': ('coarse','bisect'),
                                   'reconfirm': ('refinement',), 'extension': ('refinement',)}
                if attempt.probeStage not in expected_stages[attempt.direction]:
                    raise ValueError('sampling stage does not match direction')
                if attempt.advanceReason == 'anchor_reset' and attempt.direction != 'anchor':
                    raise ValueError('only the anchor can be reset')
            elif attempt.advanceReason == 'anchor_reset' or attempt.runEndReason == 'anchor_reset':
                raise ValueError('anchor reset requires v6')
            if self.protocolVersion in ('guided-range-v5', 'guided-range-v6', 'guided-range-v7'):
                a = attempt
                if a.evaluationStart is None:
                    if any(v is not None for v in (a.referenceStart,a.referenceEnd,a.referenceMidi,a.runEndReason)):
                        raise ValueError('an unmeasured attempt cannot select a reference')
                else:
                    if any(v is None for v in (a.referenceStart,a.referenceEnd,a.referenceMidi,a.runEndReason,a.metrics)):
                        raise ValueError('v5 requires exact reference, metrics and endpoint reason')
                    if (a.referenceStart < a.responseStart - .001 or abs(a.referenceEnd-a.referenceStart-.2) > .001 or
                        abs(a.referenceEnd-a.evaluationStart) > .001 or abs(a.referenceMidi-a.targetMidi) > 1.000001):
                        raise ValueError('invalid v5 reference window')
                    m = a.metrics
                    if any(v is None for v in (m.creditedHoldSeconds,m.bufferSeconds,m.outsideSeconds,m.excursionPenalty)):
                        raise ValueError('v5 credited time and excursion evidence required')
                    if (m.continuousHoldSeconds > m.creditedHoldSeconds + .001 or
                        abs(m.creditedHoldSeconds+m.bufferSeconds+m.outsideSeconds-(a.evaluationEnd-a.evaluationStart)) > .001 or
                        abs(m.excursionPenalty-min(20,40*(m.bufferSeconds+2*m.outsideSeconds))) > .001):
                        raise ValueError('inconsistent v5 time or penalty')
                    if self.protocolVersion == 'guided-range-v7' and m.referenceRmsCents is None:
                        raise ValueError('v7 requires fixed-reference RMS evidence')
                    qualified = m.creditedHoldSeconds >= 1 - 1e-8 and m.creditedMidi is not None
                    if qualified != (a.clientStatus == 'passed'):
                        raise ValueError('v5 pass must follow credited time and supported note')
            if self.protocolVersion == "guided-range-v4" and attempt.clientStatus == "passed" and attempt.evaluationEnd - attempt.evaluationStart < 1 - .001:
                raise ValueError("v4 pass requires at least one continuous second")
            if self.protocolVersion not in ("guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7") and (attempt.direction == "central" or attempt.progressEvaluationStart is not None or attempt.searchOutcome is not None):
                raise ValueError("v3 sampling metadata requires v3")
            if self.protocolVersion not in ("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7") and attempt.evaluationStart is not None and abs(attempt.evaluationEnd - attempt.evaluationStart - 2) > .001:
                raise ValueError("legacy evaluation must last two seconds")
            if legacy:
                if attempt.responseEnd - attempt.cueStart > 4.6 or attempt.clientStatus == "unmeasured":
                    raise ValueError("invalid legacy attempt")
            else:
                if not attempt.visitId or not attempt.advanceReason:
                    raise ValueError("v2 requires visit and advancement metadata")
                if attempt.evaluationStart is None and attempt.clientStatus in ("passed", "not_passed"):
                    raise ValueError("a measured score needs its exact two-second window")
                if attempt.clientStatus in ("unmeasured", "insufficient_quality") and attempt.evaluationStart is not None:
                    raise ValueError("unmeasured attempts cannot select a window")
                if attempt.visitId != active_visit:
                    if attempt.visitId in closed_visits:
                        raise ValueError("visit segments must be contiguous")
                    if active_visit is not None:
                        closed_visits.add(active_visit)
                    active_visit = attempt.visitId
                visits.setdefault(attempt.visitId, []).append(attempt)
            if attempt.attemptId in ids or attempt.cueStart < end - .001:
                raise ValueError("duplicate or overlapping attempts")
            ids.add(attempt.attemptId)
            end = attempt.responseEnd
            if end > self.durationSeconds + .05:
                raise ValueError("attempt outside recording")
        for group in visits.values():
            if self.protocolVersion in ("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7"):
                chosen = [a for a in group if a.selectedForVisit]
                if len(chosen) != 1:
                    raise ValueError("one representative is required")
                qualified = chosen[0].clientStatus == "passed" and group[0].advanceReason != 'anchor_reset'
                if len({(a.advanceReason, a.progressQualified, a.searchOutcome) for a in group}) != 1:
                    raise ValueError("visit advancement metadata must agree")
                a = group[0]
                expected = "reached" if qualified else "manual_failed" if a.advanceReason == "manual" else None
                if a.progressQualified != qualified or a.searchOutcome != expected or any(a.progressEvaluationStart is not None for a in group):
                    raise ValueError("v4 progress must follow the representative continuous hold")
                if a.advanceReason == "threshold" and (not qualified or chosen[0].evaluationEnd - chosen[0].evaluationStart < 1.0 - .001):
                    raise ValueError("automatic advancement requires a passed one-second hold")
            if self.protocolVersion == "guided-range-v3":
                progress = [a for a in group if a.progressEvaluationStart is not None]
                if len({(a.advanceReason, a.progressQualified, a.searchOutcome) for a in group}) != 1:
                    raise ValueError("visit advancement metadata must agree")
                a = group[0]
                if a.advanceReason == "threshold":
                    if not a.progressQualified or a.searchOutcome != "reached" or len(progress) != 1:
                        raise ValueError("automatic advancement requires exact continuous-hold evidence")
                elif a.progressQualified or progress or a.searchOutcome != ("manual_failed" if a.advanceReason == "manual" else None):
                    raise ValueError("invalid manual or interrupted advancement")
            if sum(a.selectedForVisit for a in group) != 1 or len({(a.targetMidi, a.direction) for a in group}) != 1:
                raise ValueError("a visit requires exactly one representative and one target")
            if adaptive and len({(a.anchorMidi, a.probeStage) for a in group}) != 1:
                raise ValueError('a visit must retain its anchor and sampling stage')
        if adaptive:
            for status, pair in ((self.upperBoundaryStatus, self.sampling.upperBoundaryPair),
                                 (self.lowerBoundaryStatus, self.sampling.lowerBoundaryPair)):
                if status == 'boundary_refined' and (pair is None or abs(pair[0]-pair[1]) != 1):
                    raise ValueError('a refined boundary must be adjacent')
        if any(e.t > self.durationSeconds + .05 or legacy and e.t > 90 for e in self.registerEvents):
            raise ValueError("register event outside recording")
        return self
