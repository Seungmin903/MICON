package com.micon.app.data.singanalysis

/**
 * 불러서 분석하기 녹음을 분석 파이프라인에 넘기는 **인수 지점**. 화면(`SingAnalysisViewModel`)은 이 인터페이스만 본다.
 *
 * 프론트는 녹음(WAV)과 메타(JSON)를 [SavedRecording] 으로 넘기기까지만 담당한다. 분석 담당자가 구현을 교체하면 된다
 * (`AppContainer.songAnalysis`). 후보 운영 흐름(docs/openapi.yaml, backend `RecordingController`·`VoiceAnalysisController`):
 *
 * 1. `POST /api/v1/recordings` — multipart `file`(=recording.wav), `attemptNo`, `keepOriginal` → `{ recordingId }`
 * 2. `POST /api/v1/voice-analyses` — `{ recordingId, method: "SONG", songId }` → 분석 id
 * 3. `GET /api/v1/voice-analyses/{id}` 폴링 → `GET /voice-analyses/{id}/report` → 리포트 탭 갱신
 *
 * 메타의 [SingRecordingMeta.takes] 로 카운트인·일시정지 구간을 잘라내고 WAV 시각을 곡 시각에 맞출 수 있다.
 */
interface SongAnalysisSubmitter {
    /**
     * @param onProgress 진행 문구("업로드 중 42%" 등). 화면이 그대로 보여 준다.
     */
    suspend fun submit(recording: SavedRecording, onProgress: (String) -> Unit = {}): SubmitResult
}

sealed interface SubmitResult {
    /** 접수됨. [referenceId] 는 서버 쪽 식별자(분석 id 등), 없으면 null */
    data class Accepted(val referenceId: String?, val message: String = "분석을 요청했어요. 결과는 리포트에서 확인할 수 있어요.") : SubmitResult
    /** 아직 연동되지 않음(정상 상태, 오류 아님) */
    data class Unavailable(val message: String) : SubmitResult
    /** 시도했지만 실패 */
    data class Failed(val message: String) : SubmitResult
}

/** 기본 구현: 로컬 저장까지만. 분석 연동 전까지 쓴다. */
class LocalOnlySongAnalysisSubmitter : SongAnalysisSubmitter {
    override suspend fun submit(recording: SavedRecording, onProgress: (String) -> Unit): SubmitResult =
        SubmitResult.Unavailable("분석 연동은 준비 중이에요. 녹음은 이 기기에 저장됐어요.")
}
