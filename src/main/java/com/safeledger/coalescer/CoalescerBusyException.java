package com.safeledger.coalescer;

/**
 * <b>기다림 상한에 닿았다</b> — 두 번째 계산을 시작하지 않고, 사용자에게 말이 되는 응답을 준다.
 *
 * <p>원 저장소에서는 웹 계층 공용 예외(HTTP 상태를 들고 다니는 타입)를 던졌다. 이 발췌본은
 * 웹 프레임워크를 끌고 오지 않으려고 타입을 이 하나로 좁혔다 — 코얼레서가 웹에 대해 아는 것은
 * <b>"이건 503이다"</b> 하나뿐이고, 그 하나는 {@code int} 로 충분하다.
 */
public class CoalescerBusyException extends RuntimeException {

    /** HTTP 503. int 라서 웹 프레임워크 의존이 생기지 않는다. */
    private final int status;

    public CoalescerBusyException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static CoalescerBusyException serviceUnavailable(String message) {
        return new CoalescerBusyException(503, message);
    }
}
