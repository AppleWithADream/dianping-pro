package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页结果：用于 Feed 流（关注页）的下拉刷新。
 * 与传统 offset 分页不同，用"上一次最后一条的时间 minTime + 同时间偏移量 offset"作为下次查询起点，
 * 避免翻页过程中数据变动导致重复或遗漏。
 */
@Data
public class ScrollResult {
    // 当前页数据
    private List<?> list;
    // 本次返回数据中最小的 score（即最早一条的时间戳）
    private Long minTime;
    // 与 minTime 相同的数据需要跳过的条数
    private Integer offset;
}
