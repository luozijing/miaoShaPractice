package com.travel.web.controller;

import com.travel.commons.enums.CustomerConstant;
import com.travel.commons.enums.ProductSoutOutMap;
import com.travel.commons.enums.ResultStatus;
import com.travel.commons.resultbean.ResultGeekQ;
import com.travel.commons.utils.CommonMethod;
import com.travel.commons.utils.ValidMSTime;
import com.travel.function.access.UserCheckAndLimit;
import com.travel.function.entity.MiaoShaMessage;
import com.travel.function.entity.MiaoShaUser;
import com.travel.function.entity.OrderInfo;
import com.travel.function.rabbitmq.MQSender;
import com.travel.function.redisManager.RedisClient;
import com.travel.function.redisManager.keysbean.GoodsKey;
import com.travel.function.service.RandomValidateCodeService;
import com.travel.function.zk.WatcherApi;
import com.travel.function.zk.ZkApi;
import com.travel.service.GoodsService;
import com.travel.service.MiaoShaUserService;
import com.travel.service.MiaoshaService;
import com.travel.service.OrderService;
import com.travel.vo.GoodsVo;
import com.travel.vo.MiaoShaOrderVo;
import com.travel.vo.MiaoShaUserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.List;

import static com.travel.commons.enums.CustomerConstant.MS_ING;
import static com.travel.commons.enums.ResultStatus.*;

@Controller
@RequestMapping("/miaosha")
@Slf4j
public class MiaoshaController {

    @Autowired
    MiaoShaUserService userService;

    @Autowired
    RedisClient redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    RandomValidateCodeService codeService;
    @Autowired
    MQSender sender;
    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    private ZkApi zooKeeper;


    @PostConstruct
    public void init() throws Exception {
        ResultGeekQ<List<GoodsVo>> goodsListR = goodsService.goodsVoList();
        if (!ResultGeekQ.isSuccess(goodsListR)) {
            log.error("***?????????????????????????????????***");
            return;
        }
        List<GoodsVo> goodsList = goodsListR.getData();
        for (GoodsVo goods : goodsList) {
            redisService.set(GoodsKey.getMiaoshaGoodsStock, "" + goods.getId(), goods.getStockCount());
        }

    }


    @RequestMapping(value = "/result", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<Long> miaoshaResult(Model model, MiaoShaUser user,
                                           @RequestParam("goodsId") long goodsId) {
        ResultGeekQ result = ResultGeekQ.build();
        model.addAttribute("user", user);
        try {
            if (user == null) {
                result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
                return result;
            }

            String redisK =  CommonMethod.getMiaoshaOrderWaitFlagRedisKey(String.valueOf(user.getId()), String.valueOf(goodsId));
            //??????redis???????????????????????????????????????????????????????????????
            //?????????????????????????????????????????????????????????????????????????????????????????????????????????
            if (redisService.get(redisK+ goodsId,String.class)!=null) {
                result.withError(MIAOSHA_QUEUE_ING.getCode(),MIAOSHA_QUEUE_ING.getMessage());
                return result;
            }
            String redisMr = CommonMethod.getMiaoshaOrderRedisKey(String.valueOf(user.getId()), String.valueOf(goodsId));
            //????????????????????????????????????????????????
            Object order = redisService.get(redisMr, OrderInfo.class);
            //????????????
            if(order != null) {
                OrderInfo info = (OrderInfo)order;
                result.setData(info.getId());
                return result;
            }
            result.withErrorCodeAndMessage(ResultStatus.MIAOSHA_FAIL);
            return result;
        } catch (Exception e) {
            result.withErrorCodeAndMessage(ResultStatus.MIAOSHA_FAIL);
            return result;
        }
    }


    @UserCheckAndLimit(seconds = 5, maxCount = 5, needLogin = true)
    @RequestMapping(value = "/{path}/confirm", method = RequestMethod.POST)
    @ResponseBody
    public ResultGeekQ<Integer> miaosha(MiaoShaUser user, @PathVariable("path") String path,
                                        @RequestParam("goodsId") long goodsId) {
        ResultGeekQ<Integer> result = ResultGeekQ.build();
        try {
            if (user == null) {
                result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
                return result;
            }
            //??????path
            MiaoShaUserVo userVo = new MiaoShaUserVo();
            BeanUtils.copyProperties(user, userVo);
            ResultGeekQ<Boolean> check = miaoshaService.checkPath(userVo, goodsId, path);
            if (!ResultGeekQ.isSuccess(check)) {
                result.withError(REQUEST_ILLEGAL.getCode(), REQUEST_ILLEGAL.getMessage());
                return result;
            }

            //zk ???????????? ?????????redis?????????????????????????????????redis??????????????? todo  NEW
            if (ProductSoutOutMap.productSoldOutMap.get(goodsId) != null) {
                result.withError(MIAOSHA_LOCAL_GOODS_NO.getCode(), MIAOSHA_LOCAL_GOODS_NO.getMessage());
                return result;
            }
           //*********************getMiaoshaPath***********?????????????????????????????????????????????????????????????????????????????? ???????????????   ************************
            String redisK =  CommonMethod.getMiaoshaOrderWaitFlagRedisKey(String.valueOf(user.getId()), String.valueOf(goodsId));
            if (!redisService.set(redisK,String.valueOf(goodsId), "NX", "EX", 5)) {
                result.withError(MIAOSHA_QUEUE_ING.getCode(), MIAOSHA_QUEUE_ING.getMessage());
                return result;
            }

            //???????????? ???????????????
            ResultGeekQ<GoodsVo> goodR = goodsService.goodsVoByGoodId(Long.valueOf(goodsId));
            if (!ResultGeekQ.isSuccess(goodR)) {
                result.withError(PRODUCT_NOT_EXIST.getCode(), PRODUCT_NOT_EXIST.getMessage());
                return result;
            }
            ResultGeekQ validR = ValidMSTime.validMiaoshaTime(goodR.getData());
            if (!ResultGeekQ.isSuccess(validR)) {
                result.withError(validR.getCode(), validR.getMessage());
                return result;
            }

            //?????????????????????
            ResultGeekQ<MiaoShaOrderVo> order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
            if (!ResultGeekQ.isSuccess(order)) {
                result.withError(REPEATE_MIAOSHA.getCode(), REPEATE_MIAOSHA.getMessage());
                return result;
            }

            //???????????? +  ZK ??????????????????
            ResultGeekQ<Boolean> deductR = deductStockCache(goodsId+"");
            if(!ResultGeekQ.isSuccess(deductR)){
                result.withError(deductR.getCode(), deductR.getMessage());
                return result;
            }

            //??????
            MiaoShaMessage mm = new MiaoShaMessage();
            mm.setUser(user);
            mm.setGoodsId(goodsId);
            sender.sendMiaoshaMessage(mm);
            //???????????????
            result.setData(MS_ING);
        } catch (AmqpException amqpE){
            log.error("??????????????????", amqpE);
            String goodsIdZ =  String.valueOf(goodsId);
            redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsIdZ);
            ProductSoutOutMap.productSoldOutMap.remove(goodsIdZ);
            //??????zk????????????????????????false
            if (zooKeeper.exists(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsIdZ), true) != null) {
                zooKeeper.updateNode(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsIdZ), "false");
            }
            result.withErrorCodeAndMessage(MIAOSHA_MQ_SEND_FAIL);
            return result ;
        }catch (Exception e) {
            result.withErrorCodeAndMessage(MIAOSHA_FAIL);
            return result;
        }
        return result;
    }


    @RequestMapping(value = "/verifyCode", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<String> getMiaoshaVerifyCod(HttpServletResponse response, MiaoShaUser user,
                                                   @RequestParam("goodsId") long goodsId) {
        ResultGeekQ<String> result = ResultGeekQ.build();
        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }
        try {
            BufferedImage image = codeService.getRandcode(user, goodsId);
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "JPEG", out);
            out.flush();
            out.close();
            return result;
        } catch (Exception e) {
            log.error("?????????????????????-----goodsId:{}", goodsId, e);
            result.withError(MIAOSHA_FAIL.getCode(), MIAOSHA_FAIL.getMessage());
            return result;
        }
    }

    @UserCheckAndLimit(seconds = 5, maxCount = 5, needLogin = true)
    @RequestMapping(value = "/path", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<String> getMiaoshaPath(HttpServletRequest request, MiaoShaUser user,
                                              @RequestParam("goodsId") long goodsId,
                                              @RequestParam(value = "verifyCode", defaultValue = "0") String verifyCode
    ) {
        ResultGeekQ<String> result = ResultGeekQ.build();
        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }

        MiaoShaUserVo userVo = new MiaoShaUserVo();
        BeanUtils.copyProperties(user, userVo);
        boolean check = codeService.checkVerifyCode(userVo, goodsId, verifyCode);
        if (!check) {
            result.withError(REQUEST_ILLEGAL.getCode(), REQUEST_ILLEGAL.getMessage());
            return result;
        }
        ResultGeekQ<String> pathR = miaoshaService.createMiaoshaPath(userVo, goodsId);
        if (!ResultGeekQ.isSuccess(pathR)) {
            result.withError(pathR.getCode(), pathR.getMessage());
            return result;
        }
        result.setData(pathR.getData());
        return result;
    }


    //TODO ?????????
    public ResultGeekQ<Boolean> deductStockCache(String goodsId) {

        ResultGeekQ<Boolean> resultGeekQ = ResultGeekQ.build();
        try {
            Long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
            if (stock == null) {
                log.error("***?????????????????????***");
                resultGeekQ.withError(MIAOSHA_DEDUCT_FAIL.getCode(), MIAOSHA_DEDUCT_FAIL.getMessage());
                return resultGeekQ;
            }
            if (stock < 0) {
                log.info("***stock ????????????*** stock:{}",stock);
                redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
                ProductSoutOutMap.productSoldOutMap.put(goodsId, true);
                //???zk?????????????????????true
                if (zooKeeper.exists(CustomerConstant.ZookeeperPathPrefix.PRODUCT_SOLD_OUT, false) == null) {
                    zooKeeper.createNode(CustomerConstant.ZookeeperPathPrefix.PRODUCT_SOLD_OUT,"");
                }

                if (zooKeeper.exists(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsId), true) == null) {
                    zooKeeper.createNode(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsId), "true");
                }
                if ("false".equals(new String(zooKeeper.getData(CustomerConstant.
                        ZookeeperPathPrefix.getZKSoldOutProductPath(goodsId), new WatcherApi())))) {
                    zooKeeper.updateNode(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsId), "true");
                    //??????zk??????????????????
                    zooKeeper.exists(CustomerConstant.ZookeeperPathPrefix.getZKSoldOutProductPath(goodsId), true);
                }
                resultGeekQ.withError(MIAO_SHA_OVER.getCode(), MIAO_SHA_OVER.getMessage());
                return resultGeekQ;
            }
        } catch (Exception e) {
            log.error("***deductStockCache error***");
            resultGeekQ.withError(MIAO_SHA_OVER.getCode(), MIAO_SHA_OVER.getMessage());
            return resultGeekQ;
        }
        return resultGeekQ;
    }
}