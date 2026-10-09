package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应结果：所有接口返回值都包装成该对象，前端按 success 判断成败。
 * success 是否成功、errorMsg 失败原因、data 返回数据、total 分页总条数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;

    // 成功，无数据
    public static Result ok(){
        return new Result(true, null, null, null);
    }
    // 成功，带数据
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    // 成功，带分页数据与总条数
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    // 失败，带错误信息
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
