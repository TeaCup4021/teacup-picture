import type { PrototypeRole } from "@/features/prototype/model/types";

/** AI creation is available to every authenticated application role. */
export function canUseAi(role: PrototypeRole | null | undefined): boolean {
  return role === "user" || role === "admin";
}
