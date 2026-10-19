import { exec, moduleInfo } from "kernelsu";

let baseDir: string | undefined;

function markerPath(packageName: string) {
  if (baseDir === undefined) {
    baseDir = JSON.parse(moduleInfo()).dir;
  }
  return `/data/${baseDir}/packages/${packageName}`;
}

async function getState(packageName: string) {
  const { errno } = await exec(`test -e ${markerPath(packageName)}`);
  return errno == 0;
}

async function setState(packageName: string, enabled: boolean) {
  const marker = markerPath(packageName);
  return await exec(enabled ? `touch ${marker}` : `rm -f ${marker}`);
}

export { getState, setState };
