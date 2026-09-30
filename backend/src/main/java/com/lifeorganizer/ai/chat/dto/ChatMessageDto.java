package com.lifeorganizer.ai.chat.dto;

/** date is the thread's day (yyyy-MM-dd); role is USER or ASSISTANT; proposal is null when there is none. */
public record ChatMessageDto(long id, String date, String role, String text, ProposalDto proposal, String createdAt) {
}
