package com.livecraft.broadcast;

import java.time.Instant;

public record BroadcastDto(
        Long id,
        String title,
        String description,
        Broadcast.Status status,
        Broadcast.SourceType sourceType,
        Long ownerId,
        String ownerNickname,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt
) {
    public static BroadcastDto from(Broadcast b) {
        return new BroadcastDto(b.getId(), b.getTitle(), b.getDescription(), b.getStatus(), b.getSourceType(),
                b.getOwner().getId(), b.getOwner().getNickname(), b.getCreatedAt(), b.getStartedAt(), b.getEndedAt());
    }
}
