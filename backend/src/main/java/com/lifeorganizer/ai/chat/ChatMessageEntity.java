package com.lifeorganizer.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_date")
    private LocalDate chatDate;

    private int position;

    private String role;

    @Column(columnDefinition = "text")
    private String text;

    @Column(columnDefinition = "text")
    private String proposal;

    @Column(name = "proposal_status")
    private String proposalStatus;

    @Column(name = "created_at")
    private Instant createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(LocalDate chatDate, int position, String role, String text, String proposal,
                             String proposalStatus, Instant createdAt) {
        this.chatDate = chatDate;
        this.position = position;
        this.role = role;
        this.text = text;
        this.proposal = proposal;
        this.proposalStatus = proposalStatus;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public LocalDate getChatDate() { return chatDate; }
    public int getPosition() { return position; }
    public String getRole() { return role; }
    public String getText() { return text; }
    public String getProposal() { return proposal; }
    public String getProposalStatus() { return proposalStatus; }
    public Instant getCreatedAt() { return createdAt; }

    public void setProposalStatus(String proposalStatus) {
        this.proposalStatus = proposalStatus;
    }
}
