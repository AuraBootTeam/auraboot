package com.auraboot.framework.party.dto;

import jakarta.validation.constraints.NotNull;

public record ActorSwitchRequest(@NotNull Long partyId) {}
