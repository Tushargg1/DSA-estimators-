package com.dsatracker.dto;

/** Request body for an authenticated user joining by invite code. */
public record JoinGroupRequest(String inviteCode) {
}
