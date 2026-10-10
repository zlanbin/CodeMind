package demo;

public class NotificationSender {

    public void notifyRefund(long orderId, String channel) {
        String message = buildMessage(orderId);
        dispatch(channel, message);
    }

    private String buildMessage(long orderId) {
        return "order " + orderId + " refunded";
    }

    private void dispatch(String channel, String message) {
        System.out.println(channel + " <- " + message);
    }
}
