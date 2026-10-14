package dev.genericpayments.platform.webhooks.application.port.out;
import java.util.*;
public interface CallbackHttpPort {
    enum ErrorCategory { POLICY, DNS, TIMEOUT, TLS, NETWORK, BODY_LIMIT, HTTP_STATUS, SECRET, PAYLOAD }
    record Result(boolean succeeded,Integer httpStatus,ErrorCategory error) {
        public Result {
            if(httpStatus!=null&&(httpStatus<100||httpStatus>599)||succeeded&&(httpStatus==null||httpStatus<200||httpStatus>299||error!=null)||!succeeded&&error==null)throw new IllegalArgumentException("Invalid delivery result");
        }
        public static Result success(int status){return new Result(true,status,null);}
        public static Result failed(Integer status,ErrorCategory category){return new Result(false,status,Objects.requireNonNull(category));}
    }
    Result post(String endpoint,Map<String,String> headers,byte[] body);
}
