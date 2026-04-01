package ccommit.stylehub.payment.client;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * PG사 타입에 따라 적절한 PaymentClient 구현체를 반환하는 팩토리이다.
 * 새로운 PG사 추가 시 PaymentClient 구현체만 만들면 자동으로 등록된다.
 * </p>
 */
@Component
public class PaymentClientFactory {

    private final Map<String, PaymentClient> clients;

    // Spring이 PaymentClient 구현체를 모두 주입 → getType()으로 Map에 등록
    public PaymentClientFactory(List<PaymentClient> paymentClients) {
        this.clients = paymentClients.stream()
                .collect(Collectors.toMap(PaymentClient::getType, Function.identity()));
    }

    public PaymentClient getClient(String pgType) {
        PaymentClient client = clients.get(pgType);
        if (client == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return client;
    }
}
