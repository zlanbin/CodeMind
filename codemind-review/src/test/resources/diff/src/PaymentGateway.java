package demo;

public class PaymentGateway {

    private final AuditLogger auditLogger = new AuditLogger();
    private final RefundClient refundClient = new RefundClient();

    public void refundOrder(long orderId, int amountCents) {
        auditLogger.record("refund-start", orderId);
        // 调用方按 String 传票据号，但 RefundClient 已改成 long
        refundClient.submitRefund("TICKET-" + orderId, amountCents);
        auditLogger.record("refund-done", orderId);
    }

    public boolean isRefundable(long orderId) {
        return orderId > 0;
    }
}
