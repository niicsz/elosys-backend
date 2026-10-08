package com.binitech.elosys.domain.candidate;

public record RejectedCpf(String cpf, String reason, int distinctVoterIds, int distinctNames) {}
