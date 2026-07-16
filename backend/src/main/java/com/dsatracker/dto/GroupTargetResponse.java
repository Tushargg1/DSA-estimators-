package com.dsatracker.dto;

public record GroupTargetResponse(
        Long groupId,
        int dailyTarget,
        String mode,
        int autoSuggestedTarget,
        boolean owner,
        Poll poll
) {
    public record Poll(
            boolean active,
            int version,
            int votesCast,
            int eligibleMembers,
            Integer currentUserVote
    ) {
    }
}