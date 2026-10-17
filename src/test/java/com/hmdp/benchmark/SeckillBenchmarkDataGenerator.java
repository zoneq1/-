package com.hmdp.benchmark;

import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 秒杀压测数据准备工具，在 IDE 中手动运行。
 * 类名不以 Test/Tests 结尾，因此不会被 mvn test 自动执行。
 *
 * 秒杀是"一人一单"，压测必须准备大量互不相同的登录用户，
 * 否则第二个线程就会撞上重复下单校验，压不出真实并发。
 */
@Slf4j
@SpringBootTest
class SeckillBenchmarkDataGenerator {

    /**
     * 压测用户数量，需与 JMeter 线程数一致
     */
    private static final int USER_COUNT = 1000;
    /**
     * 压测用户id起点，避开种子数据里的真实用户（1~4）
     */
    private static final long USER_ID_START = 90001L;
    /**
     * 压测券库存，需要大于用户数，这样理论上每个用户都能抢到一单
     */
    private static final int STOCK = 2000;
    private static final String BENCH_TITLE = "压测秒杀券";
    private static final String TOKEN_PREFIX = "bench-";
    private static final Path CSV_PATH = Path.of("src", "test", "jmeter", "tokens.csv");

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 第一步：造用户、造token、造券。可重复执行，每次都会重建压测券。
     */
    @Test
    void prepare() throws IOException {
        createBenchUsers();
        createBenchTokens();
        writeTokenCsv();
        Long voucherId = recreateSeckillVoucher();

        log.info("压测数据准备完成：用户 {} 个，库存 {}，voucherId = {}", USER_COUNT, STOCK, voucherId);
        log.info("在项目根目录执行压测：");
        log.info("jmeter -n -t src/test/jmeter/seckill-benchmark.jmx -JvoucherId={} -Jthreads={} -l result.jtl -e -o report",
                voucherId, USER_COUNT);
    }

    /**
     * 压测跑完后执行，核对有没有超卖。异步下单有延迟，建议等几秒再跑。
     */
    @Test
    void verify() {
        List<Voucher> vouchers = voucherService.query().eq("title", BENCH_TITLE).list();
        if (vouchers.isEmpty()) {
            log.warn("没有找到压测券，请先执行 prepare()");
            return;
        }
        Long voucherId = vouchers.get(0).getId();

        Integer dbStock = seckillVoucherService.getById(voucherId).getStock();
        Long dbOrderCount = voucherOrderService.query().eq("voucher_id", voucherId).count();
        String redisStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        Long redisOrderCount = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + voucherId);

        long sold = STOCK - dbStock;
        log.info("初始库存={} DB剩余库存={} DB订单数={} Redis剩余库存={} Redis下单用户数={}",
                STOCK, dbStock, dbOrderCount, redisStock, redisOrderCount);

        if (sold > STOCK) {
            log.error("校验失败：超卖！卖出 {} 件，库存只有 {} 件", sold, STOCK);
        } else if (dbOrderCount != sold) {
            log.error("校验失败：DB订单数 {} 与库存扣减数 {} 不一致，可能有订单还在消息队列中未消费", dbOrderCount, sold);
        } else {
            log.info("校验通过：卖出 {} 件，未超卖，DB库存扣减与订单数一致", sold);
        }
    }

    /**
     * 清理压测数据。
     * 压测用户占用了 90001 之后的 id，会把 tb_user 的自增值顶到 91001 并一直保持，
     * 导致之后正常注册的新用户 id 从 91001 开始，所以这里要把自增值回落到真实用户的 max(id)+1。
     */
    @Test
    void cleanup() {
        List<Long> voucherIds = voucherService.query().eq("title", BENCH_TITLE).list()
                .stream().map(Voucher::getId).toList();
        if (!voucherIds.isEmpty()) {
            voucherOrderService.remove(new QueryWrapper<VoucherOrder>().in("voucher_id", voucherIds));
            seckillVoucherService.removeByIds(voucherIds);
            voucherService.removeByIds(voucherIds);
            voucherIds.forEach(id -> stringRedisTemplate.delete(List.of(
                    SECKILL_STOCK_KEY + id,
                    SECKILL_ORDER_KEY + id)));
            log.info("已删除压测券 {} 及关联订单", voucherIds);
        }

        List<String> tokenKeys = new ArrayList<>(USER_COUNT);
        for (int i = 0; i < USER_COUNT; i++) {
            tokenKeys.add(LOGIN_USER_KEY + tokenOf(USER_ID_START + i));
        }
        stringRedisTemplate.delete(tokenKeys);

        int removed = jdbcTemplate.update("DELETE FROM tb_user WHERE id BETWEEN ? AND ?",
                USER_ID_START, USER_ID_START + USER_COUNT - 1);

        Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM tb_user", Long.class);
        jdbcTemplate.execute("ALTER TABLE tb_user AUTO_INCREMENT = " + (maxId + 1));

        log.info("已删除压测用户 {} 个、token {} 个，tb_user 自增值回落至 {}", removed, tokenKeys.size(), maxId + 1);
    }

    private void createBenchUsers() {
        List<Object[]> batch = new ArrayList<>(USER_COUNT);
        for (int i = 0; i < USER_COUNT; i++) {
            long userId = USER_ID_START + i;
            // 手机号用 139 号段，与种子数据里的真实用户不冲突
            batch.add(new Object[]{userId, "139" + String.format("%08d", i), "bench_" + userId, ""});
        }
        jdbcTemplate.batchUpdate(
                "INSERT IGNORE INTO tb_user (id, phone, nick_name, icon) VALUES (?, ?, ?, ?)", batch);
        log.info("压测用户就绪，id 区间 [{}, {}]", USER_ID_START, USER_ID_START + USER_COUNT - 1);
    }

    /**
     * 直接往 Redis 写登录态，跳过短信验证码流程。
     * 结构与 UserServiceImpl.login 写入的一致：login:token:{token} 是一个 hash。
     * 用 pipeline 把 2000 次网络往返压成一次。
     */
    private void createBenchTokens() {
        byte[] idField = "id".getBytes(StandardCharsets.UTF_8);
        byte[] nickNameField = "nickName".getBytes(StandardCharsets.UTF_8);
        byte[] iconField = "icon".getBytes(StandardCharsets.UTF_8);
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < USER_COUNT; i++) {
                long userId = USER_ID_START + i;
                byte[] key = (LOGIN_USER_KEY + tokenOf(userId)).getBytes(StandardCharsets.UTF_8);
                Map<byte[], byte[]> fields = new HashMap<>(4);
                fields.put(idField, String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
                fields.put(nickNameField, ("bench_" + userId).getBytes(StandardCharsets.UTF_8));
                fields.put(iconField, new byte[0]);
                connection.hashCommands().hMSet(key, fields);
                connection.keyCommands().expire(key, LOGIN_USER_TTL * 60);
            }
            return null;
        });
        log.info("登录 token 已写入 Redis，共 {} 个，前缀 {}", USER_COUNT, TOKEN_PREFIX);
    }

    private void writeTokenCsv() throws IOException {
        Files.createDirectories(CSV_PATH.getParent());
        List<String> lines = new ArrayList<>(USER_COUNT + 1);
        lines.add("token");
        for (int i = 0; i < USER_COUNT; i++) {
            lines.add(tokenOf(USER_ID_START + i));
        }
        Files.write(CSV_PATH, lines, StandardCharsets.UTF_8);
        log.info("token 清单已生成：{}", CSV_PATH.toAbsolutePath());
    }

    /**
     * 每次重建压测券，保证券id全新，seckill:order 集合天然是空的，可以重复压测。
     */
    private Long recreateSeckillVoucher() {
        List<Long> oldIds = voucherService.query().eq("title", BENCH_TITLE).list()
                .stream().map(Voucher::getId).toList();
        if (!oldIds.isEmpty()) {
            voucherService.removeByIds(oldIds);
            seckillVoucherService.removeByIds(oldIds);
            oldIds.forEach(id -> stringRedisTemplate.delete(List.of(
                    SECKILL_STOCK_KEY + id,
                    SECKILL_ORDER_KEY + id)));
            log.info("已清理上一轮的压测券 {}", oldIds);
        }

        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle(BENCH_TITLE);
        voucher.setSubTitle("压测专用");
        voucher.setRules("压测专用，请勿使用");
        voucher.setPayValue(1L);
        voucher.setActualValue(10L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(STOCK);
        voucher.setBeginTime(LocalDateTime.now().minusHours(1));
        voucher.setEndTime(LocalDateTime.now().plusDays(1));
        voucherService.addSeckillVoucher(voucher);
        return voucher.getId();
    }

    private String tokenOf(long userId) {
        return TOKEN_PREFIX + userId;
    }
}
