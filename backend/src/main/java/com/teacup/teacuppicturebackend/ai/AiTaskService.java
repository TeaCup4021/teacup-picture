package com.teacup.teacuppicturebackend.ai;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M2Dtos;
import com.teacup.teacuppicturebackend.mapper.AiModelMapper;
import com.teacup.teacuppicturebackend.mapper.AiQuotaUsageMapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskQuotaAuditMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.AiModel;
import com.teacup.teacuppicturebackend.model.entity.AiQuotaUsage;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import com.teacup.teacuppicturebackend.model.entity.AiTaskQuotaAudit;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiTaskService {
    private static final Set<String> TYPES = Set.of("generate", "outpaint");
    private static final Set<String> STATUSES = Set.of("queued", "running", "succeeded", "failed", "cancelled");
    private static final Set<String> BACKGROUNDS = Set.of("auto", "opaque", "transparent");
    private static final Set<String> OUTPUT_FORMATS = Set.of("png", "jpeg", "webp");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private final AiModelMapper modelMapper;
    private final AiQuotaUsageMapper quotaMapper;
    private final AiTaskMapper taskMapper;
    private final AiTaskQuotaAuditMapper quotaAuditMapper;
    private final PictureMapper pictureMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final PictureStorage storage;
    private final AiTaskOutboxService outboxService;
    private final int generateDailyLimit;
    private final int outpaintDailyLimit;
    private final ZoneId quotaZone;

    public AiTaskService(AiModelMapper modelMapper, AiQuotaUsageMapper quotaMapper, AiTaskMapper taskMapper,
                         AiTaskQuotaAuditMapper quotaAuditMapper,
                         PictureMapper pictureMapper, UserMapper userMapper, UserService userService, PictureStorage storage,
                         AiTaskOutboxService outboxService,
                         @Value("${teacup.ai.quota.generate-daily:100}") int generateDailyLimit,
                         @Value("${teacup.ai.quota.outpaint-daily:100}") int outpaintDailyLimit,
                         @Value("${teacup.ai.quota.zone:Asia/Shanghai}") String quotaZone) {
        this.modelMapper = modelMapper;
        this.quotaMapper = quotaMapper;
        this.taskMapper = taskMapper;
        this.quotaAuditMapper = quotaAuditMapper;
        this.pictureMapper = pictureMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.storage = storage;
        this.outboxService = outboxService;
        this.generateDailyLimit = generateDailyLimit;
        this.outpaintDailyLimit = outpaintDailyLimit;
        this.quotaZone = ZoneId.of(quotaZone);
    }

    public List<M2Dtos.AiModelView> models() {
        return modelMapper.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getEnabled, 1).orderByAsc(AiModel::getId))
                .stream().map(this::modelView).toList();
    }

    public M2Dtos.AiQuotaSummary quotas(User user) {
        LocalDate date = LocalDate.now(quotaZone);
        return new M2Dtos.AiQuotaSummary(date, List.of(quotaView(user.getId(), date, "generate"), quotaView(user.getId(), date, "outpaint")));
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateResult create(User user, M2Dtos.CreateAiTaskRequest input, String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) throw V1Exception.badRequest("Idempotency-Key 格式无效");
        if (input == null || !TYPES.contains(input.type())) throw V1Exception.badRequest("AI 任务类型无效");
        String prompt = input.prompt() == null ? "" : input.prompt().trim();
        if (prompt.isBlank() || prompt.length() > 2000) throw V1Exception.badRequest("提示词长度必须为 1 到 2000 字");
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getCode, input.modelCode()).eq(AiModel::getEnabled, 1).last("LIMIT 1"));
        if (model == null || !strings(model.getCapabilities()).contains(input.type())) throw V1Exception.badRequest("模型不支持该任务类型");
        if (!strings(model.getSupportedRatios()).contains(input.ratio())) throw V1Exception.badRequest("图片比例不受支持");
        if (!strings(model.getSupportedQualities()).contains(input.quality())) throw V1Exception.badRequest("清晰度不受支持");
        OutputOptions output = outputOptions(input, model);
        Picture source = picture(user, input.sourcePictureId(), "outpaint".equals(input.type()));
        Picture reference = picture(user, input.referencePictureId(), false);
        if (reference != null && !Integer.valueOf(1).equals(model.getSupportsReference())) throw V1Exception.badRequest("模型不支持参考图");
        userMapper.lockById(user.getId());
        AiTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getUserId, user.getId()).eq(AiTask::getIdempotencyKey, key).last("LIMIT 1"));
        if (existing != null) {
            if (!sameRequest(existing, input, prompt, output)) throw V1Exception.conflict("Idempotency-Key 已用于其他请求");
            return new CreateResult(taskView(existing), false);
        }
        int cost = Math.max(1, model.getQuotaCost());
        reserveQuota(user.getId(), input.type(), cost);
        AiTask task = new AiTask();
        task.setUserId(user.getId()); task.setIdempotencyKey(key); task.setTaskType(input.type()); task.setModelId(model.getId()); task.setModelCode(model.getCode());
        task.setProvider(model.getProvider()); task.setProviderModel(model.getProviderModel()); task.setPrompt(prompt);
        task.setRatio(input.ratio()); task.setQuality(input.quality()); task.setBackground(output.background());
        task.setOutputFormat(output.format()); task.setOutputCompression(output.compression());
        task.setSourcePictureId(source == null ? null : source.getId());
        task.setReferencePictureId(reference == null ? null : reference.getId()); task.setStatus("queued");
        task.setNextAttemptAt(LocalDateTime.now()); task.setQuotaCost(cost);
        task.setQuotaRefunded(0); task.setQuotaSettled(0); task.setInvocationStarted(0); taskMapper.insert(task);
        outboxService.enqueue(task.getId());
        return new CreateResult(taskView(task, model), true);
    }

    public M2Dtos.AiTaskPage list(User user, int page, int pageSize, String status) {
        validatePage(page, pageSize);
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw V1Exception.badRequest("任务状态无效");
        LambdaQueryWrapper<AiTask> query = new LambdaQueryWrapper<AiTask>().eq(AiTask::getUserId, user.getId())
                .orderByDesc(AiTask::getCreateTime).orderByDesc(AiTask::getId);
        if (status != null && !status.isBlank()) query.eq(AiTask::getStatus, status);
        Page<AiTask> result = taskMapper.selectPage(new Page<>(page, pageSize), query);
        return new M2Dtos.AiTaskPage(result.getRecords().stream().map(this::taskView).toList(),
                new M1Dtos.PageMeta(page, pageSize, result.getTotal(), result.getPages()));
    }

    public M2Dtos.AiTaskView get(User user, long taskId) {
        return taskView(requireOwned(user, taskId));
    }

    @Transactional(rollbackFor = Exception.class)
    public M2Dtos.AiTaskView cancel(User user, long taskId) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !Objects.equals(task.getUserId(), user.getId())) throw V1Exception.notFound();
        if (!Set.of("queued", "running").contains(task.getStatus())) throw V1Exception.conflict("任务当前不可取消");
        boolean queued = "queued".equals(task.getStatus()) && !Integer.valueOf(1).equals(task.getInvocationStarted());
        task.setStatus("cancelled"); task.setFinishTime(LocalDateTime.now()); task.setWorkerId(null); task.setLeaseUntil(null);
        // 任务尚未发起调用：退还预占。已发起调用：上游成本已产生，照常结算。
        if (queued) {
            finalizeQuota(task, true, "cancelled_before_invocation");
        } else {
            finalizeQuota(task, false, "cancelled_after_invocation");
        }
        taskMapper.updateById(task);
        return taskView(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean fail(long taskId, String workerId, long executionToken, String code, String reason) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (!ownedBy(task, workerId, executionToken)) {
            return false;
        }
        task.setStatus("failed"); task.setFailureCode(trim(code, 64)); task.setFailureReason(trim(reason, 500));
        task.setFinishTime(LocalDateTime.now()); task.setWorkerId(null); task.setLeaseUntil(null);
        finalizeQuota(task, true, "failed_" + trim(code, 64));
        taskMapper.updateById(task);
        return true;
    }

    /**
     * 尝试次数耗尽或死信回收：将任务终态化并归还预占。
     * 不校验执行者身份——调用方是抢占失败的执行者或死信消费者，此时任务已无有效持有者。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean exhaust(long taskId) {
        return exhaust(taskId, "attempts_exhausted", "AI 任务重试次数已用尽");
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean exhaust(long taskId, String code, String reason) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || terminal(task.getStatus())) return false;
        String previous = task.getStatus();
        task.setStatus("failed"); task.setFailureCode(trim(code, 64)); task.setFailureReason(trim(reason, 500));
        task.setFinishTime(LocalDateTime.now()); task.setWorkerId(null); task.setLeaseUntil(null);
        finalizeQuota(task, true, "exhausted_from_" + previous);
        taskMapper.updateById(task);
        return true;
    }

    /**
     * 对账修正入口：修复「事件路径没走完」留下的预占悬挂。
     * 只处理已终态的任务，或创建时间超过 zombieAgeMinutes 的非终态任务。
     * 正在执行且租约未过期的任务一律不动。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reconcile(long taskId, long zombieAgeMinutes) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null) return false;
        if (Integer.valueOf(1).equals(task.getQuotaSettled()) || Integer.valueOf(1).equals(task.getQuotaRefunded())) return false;
        String status = task.getStatus();
        if (terminal(status)) {
            if ("succeeded".equals(status)) {
                finalizeQuota(task, false, "settled_missing");
            } else {
                finalizeQuota(task, true, "release_missing");
            }
            taskMapper.updateById(task);
            return true;
        }
        if ("running".equals(status) && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getCreateTime() == null || task.getCreateTime().isAfter(now.minusMinutes(Math.max(1, zombieAgeMinutes)))) {
            return false;
        }
        task.setStatus("failed");
        task.setFailureCode("reconcile_zombie");
        task.setFailureReason("AI 任务长期未达终态，已由配额对账任务回收");
        task.setFinishTime(now);
        task.setWorkerId(null);
        task.setLeaseUntil(null);
        finalizeQuota(task, true, "zombie_task");
        taskMapper.updateById(task);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(long taskId, String workerId, long executionToken, String code, String reason, int delaySeconds) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (!ownedBy(task, workerId, executionToken)) {
            return false;
        }
        task.setStatus("queued");
        task.setFailureCode(trim(code, 64));
        task.setFailureReason(trim(reason, 500));
        task.setWorkerId(null);
        task.setLeaseUntil(null);
        task.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.max(1, delaySeconds)));
        taskMapper.updateById(task);
        return true;
    }

    public Download download(User user, long taskId) {
        AiTask task = requireOwned(user, taskId);
        if (!"succeeded".equals(task.getStatus()) || task.getResultPictureId() == null) throw V1Exception.conflict("任务尚无可下载结果");
        Picture picture = pictureMapper.selectById(task.getResultPictureId());
        if (picture == null) throw V1Exception.notFound();
        if (picture.getObjectKey() == null || picture.getObjectKey().isBlank()) throw V1Exception.notFound();
        PictureStorage.StoredObject object = storage.load(picture.getObjectKey());
        return new Download(object.resource(), object.fileName(), org.springframework.http.MediaType.parseMediaType(object.contentType()));
    }

    @Transactional(rollbackFor = Exception.class)
    public M2Dtos.AiModelView updateModel(User admin, long modelId, M2Dtos.UpdateAiModelRequest input) {
        if (!userService.isAdmin(admin)) throw V1Exception.forbidden();
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) throw V1Exception.notFound();
        if (input == null) throw V1Exception.badRequest("模型配置不能为空");
        if (input.name() != null && !input.name().isBlank()) model.setDisplayName(trim(input.name(), 128));
        if (input.capabilities() != null && !input.capabilities().isEmpty()) model.setCapabilities(JSONUtil.toJsonStr(input.capabilities()));
        if (input.ratios() != null && !input.ratios().isEmpty()) model.setSupportedRatios(JSONUtil.toJsonStr(input.ratios()));
        if (input.qualities() != null && !input.qualities().isEmpty()) model.setSupportedQualities(JSONUtil.toJsonStr(input.qualities()));
        if (input.backgrounds() != null && !input.backgrounds().isEmpty()) {
            validateOptions(input.backgrounds(), BACKGROUNDS, "背景模式");
            model.setSupportedBackgrounds(JSONUtil.toJsonStr(input.backgrounds()));
        }
        if (input.outputFormats() != null && !input.outputFormats().isEmpty()) {
            validateOptions(input.outputFormats(), OUTPUT_FORMATS, "输出格式");
            model.setSupportedOutputFormats(JSONUtil.toJsonStr(input.outputFormats()));
        }
        if (input.supportsOutputCompression() != null) model.setSupportsOutputCompression(input.supportsOutputCompression() ? 1 : 0);
        if (input.supportsReference() != null) model.setSupportsReference(input.supportsReference() ? 1 : 0);
        if (input.quotaCost() != null) { if (input.quotaCost() < 1 || input.quotaCost() > 100) throw V1Exception.badRequest("配额成本无效"); model.setQuotaCost(input.quotaCost()); }
        if (input.enabled() != null) model.setEnabled(input.enabled() ? 1 : 0);
        modelMapper.updateById(model);
        return modelView(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public void disableModel(long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || !Integer.valueOf(1).equals(model.getEnabled())) return;
        model.setEnabled(0);
        modelMapper.updateById(model);
    }

    public boolean isCancelled(long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        return task == null || "cancelled".equals(task.getStatus());
    }

    public boolean isTerminal(String status) {
        return terminal(status);
    }

    public void settleQuota(AiTask task) {
        if (Integer.valueOf(1).equals(task.getQuotaSettled())) return;
        AiQuotaUsage usage = lockedUsage(task);
        usage.setReservedCount(Math.max(0, nz(usage.getReservedCount()) - task.getQuotaCost()));
        usage.setUsedCount(nz(usage.getUsedCount()) + task.getQuotaCost());
        quotaMapper.updateById(usage);
        task.setQuotaSettled(1);
    }

    /**
     * 统一收尾入口：所有额度的终态流转（结算 / 归还）都必须走这里，避免散落在各出口导致漏归还。
     * 幂等，靠任务上的 quotaSettled / quotaRefunded 标记判定，重复调用无副作用。
     * 本方法不负责写 ai_task 行，由调用方在事务内 updateById。
     */
    private void finalizeQuota(AiTask task, boolean refund, String reason) {
        if (Integer.valueOf(1).equals(task.getQuotaSettled())) return;
        if (refund) {
            if (Integer.valueOf(1).equals(task.getQuotaRefunded())) return;
            releaseReservation(task);
            task.setQuotaRefunded(1);
            audit(task, "released", reason);
        } else {
            settleQuota(task);
            audit(task, "settled", reason);
        }
    }

    private void audit(AiTask task, String action, String reason) {
        AiTaskQuotaAudit record = new AiTaskQuotaAudit();
        record.setTaskId(task.getId());
        record.setUserId(task.getUserId());
        record.setTaskType(task.getTaskType());
        record.setQuotaCost(nz(task.getQuotaCost()));
        record.setUsageDate(usageDate(task));
        record.setAction(action);
        record.setReason(trim(reason, 128));
        quotaAuditMapper.insert(record);
    }

    private void reserveQuota(long userId, String type, int cost) {
        LocalDate date = LocalDate.now(quotaZone); quotaMapper.ensureRow(userId, date, type);
        AiQuotaUsage usage = quotaMapper.selectForUpdate(userId, date, type);
        if (nz(usage.getUsedCount()) + nz(usage.getReservedCount()) + cost > limit(type)) throw V1Exception.conflict("今日 AI 配额已用完");
        usage.setReservedCount(nz(usage.getReservedCount()) + cost); quotaMapper.updateById(usage);
    }

    private void releaseReservation(AiTask task) {
        AiQuotaUsage usage = lockedUsage(task);
        usage.setReservedCount(Math.max(0, nz(usage.getReservedCount()) - task.getQuotaCost()));
        quotaMapper.updateById(usage);
    }

    private AiQuotaUsage lockedUsage(AiTask task) {
        quotaMapper.ensureRow(task.getUserId(), usageDate(task), task.getTaskType());
        return quotaMapper.selectForUpdate(task.getUserId(), usageDate(task), task.getTaskType());
    }

    /** 任务归属的额度日期：createTime 按 UTC 解释后转换到 quotaZone。所有对账路径必须复用此处。 */
    private LocalDate usageDate(AiTask task) {
        return task.getCreateTime() == null ? LocalDate.now(quotaZone)
                : task.getCreateTime().atZone(ZoneOffset.UTC).withZoneSameInstant(quotaZone).toLocalDate();
    }

    private M2Dtos.AiQuotaView quotaView(long userId, LocalDate date, String type) {
        AiQuotaUsage usage = quotaMapper.selectOne(new LambdaQueryWrapper<AiQuotaUsage>().eq(AiQuotaUsage::getUserId, userId)
                .eq(AiQuotaUsage::getUsageDate, date).eq(AiQuotaUsage::getTaskType, type));
        int used = usage == null ? 0 : nz(usage.getUsedCount());
        int reserved = usage == null ? 0 : nz(usage.getReservedCount());
        int limit = limit(type);
        return new M2Dtos.AiQuotaView(type, limit, used, reserved, Math.max(0, limit - used - reserved));
    }

    private int limit(String type) { return "generate".equals(type) ? generateDailyLimit : outpaintDailyLimit; }
    private Picture picture(User user, String id, boolean required) {
        if (id == null || id.isBlank()) { if (required) throw V1Exception.badRequest("扩图必须选择原图"); return null; }
        long parsed; try { parsed = Long.parseLong(id); } catch (NumberFormatException exception) { throw V1Exception.badRequest("图片 ID 无效"); }
        Picture picture = pictureMapper.selectById(parsed);
        if (picture == null || !Objects.equals(picture.getUserId(), user.getId()) || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        return picture;
    }
    private AiTask requireOwned(User user, long taskId) { AiTask task = taskMapper.selectById(taskId); if (task == null || !Objects.equals(task.getUserId(), user.getId())) throw V1Exception.notFound(); return task; }
    private M2Dtos.AiTaskView taskView(AiTask task) { return taskView(task, modelMapper.selectById(task.getModelId())); }
    private M2Dtos.AiTaskView taskView(AiTask task, AiModel model) {
        return new M2Dtos.AiTaskView(task.getId().toString(), task.getTaskType(), modelView(model), task.getPrompt(), task.getRatio(), task.getQuality(),
                defaultOption(task.getBackground(), "auto"), defaultOption(task.getOutputFormat(), "png"),
                task.getOutputCompression(), task.getStatus(),
                pictureRef(task.getSourcePictureId(), false), pictureRef(task.getReferencePictureId(), false), pictureRef(task.getResultPictureId(), true),
                task.getFailureCode(), task.getFailureReason(), Integer.valueOf(1).equals(task.getQuotaRefunded()),
                Integer.valueOf(1).equals(task.getQuotaSettled()), instant(task.getCreateTime()), instant(task.getStartTime()), instant(task.getFinishTime()),
                "succeeded".equals(task.getStatus()) ? "/api/v1/ai/tasks/" + task.getId() + "/download" : null);
    }
    private M2Dtos.AiModelView modelView(AiModel model) {
        return new M2Dtos.AiModelView(model.getId().toString(), model.getCode(), model.getDisplayName(),
                strings(model.getCapabilities()), strings(model.getSupportedRatios()), strings(model.getSupportedQualities()),
                options(model.getSupportedBackgrounds(), "auto"), options(model.getSupportedOutputFormats(), "png"),
                Integer.valueOf(1).equals(model.getSupportsOutputCompression()),
                Integer.valueOf(1).equals(model.getSupportsReference()), model.getQuotaCost(), Integer.valueOf(1).equals(model.getEnabled()));
    }
    private M2Dtos.AiPictureRef pictureRef(Long id, boolean includeUrl) { if (id == null) return null; Picture p = pictureMapper.selectById(id); return p == null ? null : new M2Dtos.AiPictureRef(p.getId().toString(), p.getName(), p.getThumbnailUrl(), includeUrl ? p.getUrl() : null); }
    private List<String> strings(String json) { return json == null ? List.of() : JSONUtil.toList(json, String.class); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static String trim(String value, int max) { if (value == null) return null; value = value.trim(); return value.length() > max ? value.substring(0, max) : value; }
    private boolean sameRequest(AiTask task, M2Dtos.CreateAiTaskRequest input, String prompt, OutputOptions output) {
        return Objects.equals(task.getTaskType(), input.type()) && Objects.equals(task.getModelCode(), input.modelCode())
                && Objects.equals(task.getPrompt(), prompt) && Objects.equals(task.getRatio(), input.ratio())
                && Objects.equals(task.getQuality(), input.quality())
                && Objects.equals(defaultOption(task.getBackground(), "auto"), output.background())
                && Objects.equals(defaultOption(task.getOutputFormat(), "png"), output.format())
                && Objects.equals(task.getOutputCompression(), output.compression())
                && Objects.equals(id(task.getSourcePictureId()), blank(input.sourcePictureId()))
                && Objects.equals(id(task.getReferencePictureId()), blank(input.referencePictureId()));
    }
    private static OutputOptions outputOptions(M2Dtos.CreateAiTaskRequest input, AiModel model) {
        List<String> modelBackgrounds = options(model.getSupportedBackgrounds(), "auto");
        List<String> modelFormats = options(model.getSupportedOutputFormats(), "png");
        String background = defaultOption(input.background(), modelBackgrounds.get(0));
        String format = defaultOption(input.outputFormat(), modelFormats.get(0));
        if (!BACKGROUNDS.contains(background)) throw V1Exception.badRequest("背景模式不受支持");
        if (!OUTPUT_FORMATS.contains(format)) throw V1Exception.badRequest("输出格式不受支持");
        if (!modelBackgrounds.contains(background)) {
            throw V1Exception.badRequest("当前模型不支持该背景模式");
        }
        if (!modelFormats.contains(format)) {
            throw V1Exception.badRequest("当前模型不支持该输出格式");
        }
        Integer compression = input.outputCompression();
        if (compression != null && (compression < 0 || compression > 100)) {
            throw V1Exception.badRequest("输出压缩质量必须为 0 到 100");
        }
        if ("png".equals(format) && compression != null) {
            throw V1Exception.badRequest("PNG 输出不支持有损压缩质量");
        }
        if (compression != null && !Integer.valueOf(1).equals(model.getSupportsOutputCompression())) {
            throw V1Exception.badRequest("当前模型不支持输出压缩质量");
        }
        if ("transparent".equals(background) && "jpeg".equals(format)) {
            throw V1Exception.badRequest("透明背景不支持 JPEG 输出");
        }
        return new OutputOptions(background, format, compression);
    }
    private static String defaultOption(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static List<String> options(String json, String fallback) {
        List<String> values = json == null ? List.of() : JSONUtil.toList(json, String.class);
        return values.isEmpty() ? List.of(fallback) : values;
    }
    private static void validateOptions(List<String> values, Set<String> allowed, String label) {
        if (values.stream().anyMatch(value -> value == null || !allowed.contains(value))) {
            throw V1Exception.badRequest(label + "配置无效");
        }
    }
    private static int nz(Integer value) { return value == null ? 0 : value; }
    private static long token(AiTask task) { return task == null || task.getExecutionToken() == null ? 0L : task.getExecutionToken(); }
    private static boolean terminal(String status) { return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status); }
    /** 终态归属校验：状态、执行者标识、执行令牌三者同时匹配才算「本实例仍持有该任务」。 */
    private static boolean ownedBy(AiTask task, String workerId, long executionToken) {
        return task != null && "running".equals(task.getStatus())
                && Objects.equals(workerId, task.getWorkerId()) && token(task) == executionToken;
    }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void validatePage(int page, int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 100) throw V1Exception.badRequest("分页参数无效"); }

    public record Download(Resource resource, String fileName, org.springframework.http.MediaType mediaType) {}
    public record CreateResult(M2Dtos.AiTaskView task, boolean created) {}
    private record OutputOptions(String background, String format, Integer compression) {}
}
