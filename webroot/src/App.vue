<script setup lang="ts">
import { getState, setState } from "./utils/state";

type Host = {
  label: string;
  packageName: string;
  enabled: boolean;
  known: boolean;
  loading: boolean;
  error: string;
};

const hosts = reactive<Host[]>([
  {
    label: "HOOK QQ",
    packageName: "com.tencent.mobileqq",
    enabled: false,
    known: false,
    loading: false,
    error: "",
  },
  {
    label: "HOOK 微信",
    packageName: "com.tencent.mm",
    enabled: false,
    known: false,
    loading: false,
    error: "",
  },
  {
    label: "HOOK 抖音",
    packageName: "com.ss.android.ugc.aweme",
    enabled: false,
    known: false,
    loading: false,
    error: "",
  },
]);

const refresh = async (host: Host) => {
  host.loading = true;
  host.error = "";
  try {
    host.enabled = await getState(host.packageName);
    host.known = true;
  } catch (error) {
    host.error = `读取状态失败：${error instanceof Error ? error.message : String(error)}`;
  } finally {
    host.loading = false;
  }
};

const update = async (host: Host, enabled: boolean) => {
  host.loading = true;
  host.error = "";
  try {
    const result = await setState(host.packageName, enabled);
    if (result.errno === 0) {
      host.enabled = enabled;
      host.known = true;
    } else {
      const output = [result.stderr, result.stdout]
        .filter(Boolean)
        .join("\n")
        .trim();
      host.error = output || `切换失败（退出码 ${result.errno}）`;
    }
  } catch (error) {
    host.error = `修改失败：${error instanceof Error ? error.message : String(error)}`;
  } finally {
    host.loading = false;
  }
};

onBeforeMount(async () => {
  await Promise.all(hosts.map(refresh));
});
</script>

<template>
  <n-page-header title="QQ Zygisk"> </n-page-header>
  <n-flex class="my-1em" style="gap: 8px 12px">
    <n-card
      title="设置"
      :segmented="{
        content: true,
        footer: 'soft',
      }"
    >
      <n-flex vertical size="large">
        <div v-for="host in hosts" :key="host.packageName">
          <div class="form">
            <span>{{ host.label }}</span>
            <n-switch
              :value="host.enabled"
              :loading="host.loading"
              :disabled="!host.known"
              :aria-label="host.label"
              @update:value="(value: boolean) => update(host, value)"
            />
          </div>
          <pre v-if="host.error" class="host-error">{{ host.error }}</pre>
        </div>
      </n-flex>
      <div class="hint">修改后需重启对应应用生效</div>
    </n-card>
  </n-flex>
</template>

<style scoped>
.form {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.host-error {
  margin: 8px 0 0;
  color: #d03050;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.hint {
  margin-top: 12px;
  color: #909399;
  font-size: 12px;
}
</style>
