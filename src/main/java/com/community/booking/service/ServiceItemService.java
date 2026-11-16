package com.community.booking.service;

import com.community.booking.dto.ItemSaveDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.vo.HotServiceVO;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceItemVO;

import java.util.List;

/**
 * 服务项目业务接口：居民端服务广场（读，仅上架）+ 管理员服务管理（写）+ 热门榜单。
 * <p>三视角读差异由 Service 层内部固化：居民端强制 status=1（下架项目对居民
 * 不可见且不可下单）；管理员端全量可筛。热门榜单 community:service:hot 的
 * 读路径在 Batch 3 起接 Redis ZSET，本批次先落库内 hot_score 口径，
 * 方法签名保持不变。</p>
 *
 * @author community-booking
 */
public interface ServiceItemService {

    /**
     * 居民端服务分页（仅上架，热度降序）。
     *
     * @param query      分页查询入参（keyword 模糊匹配项目名/说明）
     * @param categoryId 分类筛选（可空=不限）
     * @return 服务项目 VO 分页（categoryName 与技能标签列表已补全）
     */
    PageVO<ServiceItemVO> pageItemsForResident(PageQueryDTO query, Long categoryId);

    /**
     * 管理员端服务分页（全量，可按上下架状态筛选）。
     *
     * @param query      分页查询入参
     * @param categoryId 分类筛选（可空=不限）
     * @param status     上下架筛选（可空=不限）
     * @return 服务项目 VO 分页
     */
    PageVO<ServiceItemVO> pageItemsForAdmin(PageQueryDTO query, Long categoryId, Integer status);

    /**
     * 服务项目详情（居民与管理端共用；居民端访问下架项目抛 NotFoundException）。
     *
     * @param id         项目ID
     * @param forResident 是否为居民端视角（true 时下架项目不可见）
     * @return 服务项目 VO
     */
    ServiceItemVO getItemDetail(Long id, boolean forResident);

    /**
     * 热门服务 TOP-N（下单行为驱动的热度榜；Batch 3 起优先读 Redis ZSET，
     * 不可用时回退本方法口径）。
     *
     * @param limit 取前 N 条（Service 层夹取到 1~20）
     * @return 热门条目列表
     */
    List<HotServiceVO> listHotServices(int limit);

    /**
     * 重建热门服务榜单（每日 03:00 定时任务调用）。
     * <p>以库内 hot_score 为权威口径重刷 Redis ZSET：下单时的 ZINCRBY 是"尽力而为"
     * 的增量维护（Redis 故障只 WARN），长期运行后分值可能与库内列漂移；每日一次
     * 全量重建能把榜单校准回库内真值，也让"人工修正 hot_score"能生效。</p>
     *
     * @return 重建写入的榜单成员数（Redis 不可用时为 0，榜单下次读会再触发重建）
     */
    int refreshHotRanking();

    /**
     * 新增或修改服务项目（仅管理员）。
     * <p>业务规则：项目名称排除自身查重；categoryId 须真实存在（防悬挂）；
     * unit 限定白名单；skill_required 每个标签须存在于师傅技能词表——
     * 否则该服务永远无师傅可承接，派单必然失败。</p>
     *
     * @param dto 项目保存入参
     */
    void saveItem(ItemSaveDTO dto);

    /**
     * 删除服务项目：该项目已被订单引用（无论状态）时禁止物理删除，
     * 保证订单的 service_id 永不悬挂、价格快照可追溯；
     * 未被引用的项目删除时顺带清理封面图附件。
     *
     * @param id 项目ID
     */
    void removeItem(Long id);

    /**
     * 上架/下架切换（管理员运营开关，下架后居民端不可见不可下单）。
     *
     * @param id     项目ID
     * @param status 目标状态：1上架/0下架
     */
    void toggleStatus(Long id, Integer status);

    /**
     * 绑定服务项目封面图（两步式上传的回写步骤：先经 /api/file/upload 落盘
     * 拿到相对路径，再调本方法写入 cover_img；ItemSaveDTO 刻意不含封面字段，
     * 避免封面路径随表单往返被覆盖丢失）。
     * <p>切换封面时先更新记录再清理旧附件，保证"更新失败则旧封面仍可访问"；
     * 同一路径重复提交幂等返回。</p>
     *
     * @param itemId    项目ID
     * @param coverPath 附件相对存储路径（FileUploadUtils.save 的返回值）
     */
    void saveCoverImage(Long itemId, String coverPath);

    /**
     * 取服务项目封面的相对存储路径（供图片直读接口按ID定位已落盘文件）。
     * <p>本方法承载"项目不存在/未设封面"两类 404 判定，使 Controller 无需
     * 注入 Mapper 即可定位附件（分层铁律：Controller 只调 Service）。</p>
     *
     * @param itemId 项目ID
     * @return 封面相对路径；项目不存在或未设封面时抛 NotFoundException
     */
    String getCoverPath(Long itemId);
}
