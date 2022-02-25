package com.travel.order.apis.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class MiaoShaGoods {
    private Long id;

    private Long goodsId;

    private BigDecimal miaoshaPrice;

    private Integer lockStock;

    private Integer stockCount;

    private Date startDate;

    private Date endDate;

}