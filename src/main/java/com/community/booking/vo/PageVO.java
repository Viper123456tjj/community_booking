package com.community.booking.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.time.LocalDateTime;


/**
 * 通用分页结果 VO（Service 层把 MyBatis-Plus Page 转换为本 VO 后返回，
 * 避免持久层分页对象直接泄漏到 Controller/前端）。
 *
 * @param <T> 列表元素类型（具体业务 VO）
 * @author community-booking
 */
@Data
public class PageVO<T> implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 当前页码（从1开始） */
    private Long pageNum;

    /** 每页条数 */
    private Long pageSize;

    /** 总记录数（前端计算总页数用） */
    private Long total;

    /** 总页数 */
    private Long pages;

    /** 当前页数据（已脱敏/联表补全的业务 VO 列表） */
    private List<T> records;

    /**
     * 把 MyBatis-Plus 分页对象转换为本 VO（Service 层统一出口，避免持久层
     * 分页对象泄漏到 Controller 与前端 JSON）。
     *
     * @param source MyBatis-Plus 分页结果
     * @param <T>    列表元素类型
     * @return 精简后的分页 VO（仅保留前端需要的 5 个字段）
     */
    public static <T> PageVO<T> of(IPage<T> source) {
        PageVO<T> vo = new PageVO<>();
        vo.setPageNum(source.getCurrent());
        vo.setPageSize(source.getSize());
        vo.setTotal(source.getTotal());
        vo.setPages(source.getPages());
        vo.setRecords(source.getRecords());
        return vo;
    }
}
