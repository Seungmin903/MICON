package com.micon.backend.voice.domain;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="voice_analysis_job")
public class VoiceAnalysisJob {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="analysis_job_id") private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="session_id") private VoiceAnalysisSession session;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="recording_id") private VoiceRecording recording;
 @Column(name="request_id",nullable=false,unique=true,length=100) private String requestId;
 @Column(name="ai_job_id",unique=true,length=255) private String aiJobId;
 @Column(name="analysis_type",nullable=false,length=30) private String analysisType;
 @Column(name="attempt_no",nullable=false) private int attemptNo;
 @Column(nullable=false,length=30) private String status;
 @Column(name="failure_code",length=100) private String failureCode;
 @Column(name="failure_reason",length=1000) private String failureReason;
 @Column(name="started_at") private LocalDateTime startedAt; @Column(name="completed_at") private LocalDateTime completedAt;
 @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt; @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
 protected VoiceAnalysisJob(){}
 public VoiceAnalysisJob(VoiceAnalysisSession s,VoiceRecording r,String requestId,int attempt){this.session=s;this.recording=r;this.requestId=requestId;this.analysisType="TIMBRE";this.attemptNo=attempt;this.status="PENDING";this.createdAt=LocalDateTime.now();this.updatedAt=createdAt;}
 public Long getId(){return id;} public VoiceAnalysisSession getSession(){return session;} public VoiceRecording getRecording(){return recording;} public String getRequestId(){return requestId;} public String getAiJobId(){return aiJobId;} public int getAttemptNo(){return attemptNo;} public String getStatus(){return status;} public String getFailureCode(){return failureCode;} public String getFailureReason(){return failureReason;} public LocalDateTime getStartedAt(){return startedAt;} public LocalDateTime getCompletedAt(){return completedAt;}
 public void queued(String value){if(aiJobId!=null&&!aiJobId.equals(value))throw new IllegalStateException();aiJobId=value;status="QUEUED";startedAt=LocalDateTime.now();updatedAt=startedAt;}
 public void running(LocalDateTime at){if(!terminal()){status="RUNNING";if(startedAt==null)startedAt=at;updatedAt=at;}}
 public void dispatchUnknown(String reason){status="DISPATCH_UNKNOWN";failureCode="AI_DISPATCH_UNKNOWN";failureReason=reason;updatedAt=LocalDateTime.now();}
 public void complete(LocalDateTime at){status="COMPLETED";completedAt=at;updatedAt=at;} public void fail(String c,String r,LocalDateTime at){status="FAILED";failureCode=c;failureReason=r;completedAt=at;updatedAt=at;} public void cancel(LocalDateTime at){status="CANCELED";completedAt=at;updatedAt=at;} public boolean terminal(){return "COMPLETED".equals(status)||"FAILED".equals(status)||"CANCELED".equals(status);}
}
