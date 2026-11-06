package com.financetracker.repository.context;

import java.util.UUID;

public interface UserTransactionContext {
    void setUser(UUID verifiedUserId);
}
