package com.travel.order.providers.mapper;


import com.travel.order.providers.entity.miaosha.MiaoShaOrder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MiaoShaOrderDao {
    int deleteByPrimaryKey(Long id);

    int insertSelective(MiaoShaOrder record);

    MiaoShaOrder selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(MiaoShaOrder record);

    MiaoShaOrder getMiaoshaOrderByUserIdGoodsId(@Param("userId")long userId, @Param("goodsId")long goodsId);

    List<MiaoShaOrder> listByGoodsId(@Param("goodsId") long goodsId);
}