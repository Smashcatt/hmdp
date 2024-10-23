package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author Greyson
 * @create 2024/10/23 - 16:01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScrollResult {
    private List<?> blogs;
    private Long minTime;
    private Integer offset;
}
