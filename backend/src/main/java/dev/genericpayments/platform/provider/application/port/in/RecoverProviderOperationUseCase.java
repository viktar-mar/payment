package dev.genericpayments.platform.provider.application.port.in;

import dev.genericpayments.platform.provider.application.model.RecoverProviderOperationCommand;

public interface RecoverProviderOperationUseCase {
    void recover(RecoverProviderOperationCommand command);
}
