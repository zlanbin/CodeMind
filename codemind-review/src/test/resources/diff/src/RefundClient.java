package demo;

public class RefundClient {

    public void submitRefund(long ticketId, int amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        send(ticketId, amountCents);
    }

    private void send(long ticketId, int amountCents) {
        System.out.println("refund " + ticketId + " " + amountCents);
    }
}
