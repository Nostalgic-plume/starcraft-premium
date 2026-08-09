package com.shop.config;

import cn.dev33.satoken.stp.StpUtil;
import org.apache.ibatis.reflection.SystemMetaObject;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商家数据隔离拦截器
 *
 * 当当前登录用户为商家（merchant 角色）时，自动在针对 product、orders 等包含 shop_id 字段的表的
 * SELECT / UPDATE / DELETE SQL 后追加 {@code AND `shop_id` = ?} 条件，实现数据行级隔离。
 *
 * 设计原则：
 * <ul>
 *   <li>仅 merchant 角色生效，admin 和普通用户不受影响</li>
 *   <li>仅拦截包含 shop_id 字段的业务表（product / orders）</li>
 *   <li>INSERT 不拦截（shop_id 已在 Service 层手动填充）</li>
 *   <li>SQL 中已包含 shop_id 条件的不重复追加</li>
 *   <li>DDL 语句（OPTIMIZE TABLE 等）因无 FROM/UPDATE 关键词而自动跳过</li>
 *   <li>SKIP_MAPPERS 白名单内的 Mapper 方法豁免隔离（多表 JOIN / 子查询等复杂 SQL）</li>
 * </ul>
 *
 * @author shop
 */
@Slf4j
public class ShopDataIsolationInterceptor implements InnerInterceptor {

    /** 包含 shop_id 字段、需要自动隔离的业务表 */
    private static final Set<String> SHOP_TABLES = Set.of("product", "orders");

    /**
     * 需要豁免 shop_id 自动隔离的 Mapper 方法白名单
     * （key 为 Mapper 方法的全限定 ID，如 "com.shop.mapper.xxxMapper.selectXxx"）
     *
     * 用途：拦截器基于正则解析 SQL，遇到多表 JOIN（字段歧义）、子查询、手写复杂 SQL 时，
     * 直接追加裸 shop_id 会产生 Column ambiguous 或语义错误。这类方法在此显式豁免，
     * 改为在 Service 层手动拼接 shop_id 条件。默认空集合不影响现有行为。
     */
    private static final Set<String> SKIP_MAPPERS = Set.of();

    /** 正则：提取 FROM 或 JOIN 后的表名（忽略反引号和别名） */
    private static final Pattern TABLE_AFTER_FROM_JOIN =
            Pattern.compile("(?:FROM|JOIN)\\s+`?([a-zA-Z_]\\w*)`?(?:\\s+(?:AS\\s+)?\\w+)?",
                    Pattern.CASE_INSENSITIVE);

    /** 正则：提取 UPDATE 后的表名 */
    private static final Pattern TABLE_AFTER_UPDATE =
            Pattern.compile("UPDATE\\s+`?([a-zA-Z_]\\w*)`?",
                    Pattern.CASE_INSENSITIVE);


    /** 分隔关键词：在这些关键词之前插入 WHERE / AND shop_id = ? */
    private static final Pattern SPLIT_KEYWORDS = Pattern.compile(
            "\\b(ORDER BY|GROUP BY|LIMIT|HAVING|UNION|INTERSECT|EXCEPT|FOR UPDATE|LOCK IN SHARE MODE)\\b",
            Pattern.CASE_INSENSITIVE);

    // ======================== InnerInterceptor 接口 ========================

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {
        if (!isMerchantSession()) return;
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct != SqlCommandType.SELECT) return;
        doFilter(ms, boundSql);
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        MappedStatement ms = getMappedStatement(sh);
        if (ms == null) return;
        if (!isMerchantSession()) return;
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct != SqlCommandType.UPDATE && sct != SqlCommandType.DELETE) return;
        doFilter(ms, sh.getBoundSql());
    }

    // ======================== 核心过滤逻辑 ========================

    /**
     * 对 SQL 执行 shop_id 条件追加（如果目标表在隔离列表中且 SQL 尚未包含 shop_id 条件）
     */
    private void doFilter(MappedStatement ms, BoundSql boundSql) {
        // 白名单豁免：命中该 Mapper 方法则跳过 shop_id 自动拼接
        if (SKIP_MAPPERS.contains(ms.getId())) return;

        String originalSql = boundSql.getSql();
        String tableName = extractTableName(originalSql);
        if (tableName == null || !SHOP_TABLES.contains(tableName)) return;

        // 如果 SQL 中已包含 shop_id 条件的引用，不重复追加
        if (containsShopIdCondition(originalSql)) return;

        String newSql = appendShopIdCondition(originalSql);
        if (newSql == null || newSql.equals(originalSql)) return;

        // 通过反射将修改后的 SQL 写回 BoundSql（sql 字段为 final）
        SystemMetaObject.forObject(boundSql).setValue("sql", newSql);

        // 注册 shop_id 参数映射
        addShopIdParameter(ms, boundSql);

        log.debug("商家数据隔离已生效 —— 表: [{}], Mapper: [{}]", tableName, ms.getId());
    }

    // ======================== SQL 解析 ========================

    /**
     * 从 SQL 中提取第一张操作表的名称（去除反引号、别名）
     */
    static String extractTableName(String sql) {
        // 尝试匹配 UPDATE 模式
        Matcher updateMatcher = TABLE_AFTER_UPDATE.matcher(sql);
        if (updateMatcher.find()) {
            return updateMatcher.group(1).toLowerCase();
        }

        // 尝试匹配 FROM / JOIN 模式
        Matcher fromJoinMatcher = TABLE_AFTER_FROM_JOIN.matcher(sql);
        if (fromJoinMatcher.find()) {
            return fromJoinMatcher.group(1).toLowerCase();
        }

        return null;
    }

    /**
     * 检查 SQL 中是否已包含 shop_id 条件（避免重复追加）
     */
    static boolean containsShopIdCondition(String sql) {
        // 用正则匹配 \`?shop_id\`?\s*= 或表别名限定的 a\`?\.\`?shop_id\`?\s*=
        // 如: shop_id =, `shop_id` =, o.shop_id =, `o`.`shop_id` =
        Pattern p = Pattern.compile(
                "(?:\\w+\\.)?`?shop_id`?\\s*=",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(sql).find();
    }

    /**
     * 在 SQL 的合适位置追加 {@code AND `shop_id` = ?}（无 WHERE 时补全 WHERE 子句）
     */
    static String appendShopIdCondition(String sql) {
        String upper = sql.toUpperCase().trim();

        int whereIdx = indexOfWhereKeyword(upper);
        if (whereIdx >= 0) {
            // 有 WHERE → 在 ORDER BY / GROUP BY / LIMIT / UNION 等结尾关键词之前插入
            int insertPos = indexOfSplitKeyword(upper, whereIdx + 5);
            if (insertPos < 0) {
                return sql + " AND `shop_id` = ?";
            }
            return sql.substring(0, insertPos) + " AND `shop_id` = ?" + sql.substring(insertPos);
        }

        // 无 WHERE → 在 ORDER BY / GROUP BY / LIMIT 之前插入，或末尾追加
        int insertPos = indexOfSplitKeyword(upper, 0);
        if (insertPos < 0) {
            return sql + " WHERE `shop_id` = ?";
        }
        return sql.substring(0, insertPos) + " WHERE `shop_id` = ? " + sql.substring(insertPos);
    }

    /**
     * 查找 SQL 中独立关键词 WHERE 的位置（排除函数参数或字符串中的误匹配）
     */
    private static int indexOfWhereKeyword(String upper) {
        // WHERE 必须是独立的单词——前一个字符不是字母数字或下划线
        return indexOfWord(upper, "WHERE", 0);
    }

    /**
     * 从 given 位置开始查找第一个 SPLIT_KEYWORDS 匹配的位置
     */
    private static int indexOfSplitKeyword(String upper, int fromIndex) {
        Matcher matcher = SPLIT_KEYWORDS.matcher(upper);
        if (matcher.find(fromIndex)) {
            return matcher.start();
        }
        return -1;
    }

    /**
     * 查找独立单词 word 在字符串中的位置（word 前后必须是单词边界）
     */
    private static int indexOfWord(String str, String word, int fromIndex) {
        int idx = str.indexOf(word, fromIndex);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(str.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= str.length() || !Character.isLetterOrDigit(str.charAt(end));
            if (leftOk && rightOk) {
                return idx;
            }
            idx = str.indexOf(word, idx + 1);
        }
        return -1;
    }

    // ======================== 参数绑定 ========================

    /**
     * 向 BoundSql 注册 shop_id 参数映射，使新增的 {@code ?} 占位符能被正确赋值
     */
    private void addShopIdParameter(MappedStatement ms, BoundSql boundSql) {
        String paramName = "_shopId";
        // 避免重复添加（同一条 SQL 可能被多次拦截）
        boolean alreadyExists = boundSql.getParameterMappings().stream()
                .anyMatch(m -> paramName.equals(m.getProperty()));
        if (alreadyExists) return;

        ParameterMapping mapping = new ParameterMapping.Builder(
                ms.getConfiguration(), paramName, Long.class).build();
        boundSql.getParameterMappings().add(mapping);
        boundSql.setAdditionalParameter(paramName, getCurrentShopId());
    }

    // ======================== 会话检查 ========================

    /**
     * 判断当前会话是否为商家
     *
     * 商家登录时通过 {@code StpUtil.login(shopId, "shop")} 将设备类型（deviceType）标记为 "shop"，
     * 这里直接读设备类型判断：判断动作完全在 Sa-Token 的 token 存储（Redis）中完成，不触发任何数据库查询。
     *
     * 为什么要这样判断（历史教训）：
     * 早期实现用 {@code StpUtil.hasRole("merchant")}，它会回调 {@code StpInterfaceImpl.getRoleList()}
     * 查询 user_role 表；而这条 SQL 又会被本 MybatisPlusInterceptor 拦截 → 再次进入 isMerchantSession
     * → 无限递归 StackOverflowError。改用 deviceType 判断后彻底切断这条回路。
     *
     * 另注意：Sa-Token 的 {@code StpUtil.login(id, "shop")} 第二个参数是"设备类型"而非 loginType，
     * 故 StpInterfaceImpl 中按 loginType 的 "shop" 分支实际不会被命中。
     */
    private boolean isMerchantSession() {
        try {
            return StpUtil.isLogin() && "shop".equals(StpUtil.getLoginDeviceType());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前商家的 shop_id（即 Sa-Token loginId）
     */
    private long getCurrentShopId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 从 StatementHandler 反射获取 MappedStatement（beforePrepare 参数中不直接提供）
     */
    private MappedStatement getMappedStatement(StatementHandler sh) {
        try {
            return (MappedStatement) SystemMetaObject.forObject(sh)
                    .getValue("delegate.mappedStatement");
        } catch (Exception e) {
            return null;
        }
    }
}
