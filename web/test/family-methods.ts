import type { PantryClient } from "../src/api/client"
import type { Family } from "../src/api/types"

export function unusedFamilyMethods(): Pick<PantryClient, "renameFamily" | "resetInvite" | "transferAdmin" | "removeMember" | "leaveFamily"> {
  return {
    renameFamily: async (): Promise<Family> => {
      throw new Error("family unused")
    },
    resetInvite: async (): Promise<Family> => {
      throw new Error("family unused")
    },
    transferAdmin: async (): Promise<Family> => {
      throw new Error("family unused")
    },
    removeMember: async () => {
      throw new Error("family unused")
    },
    leaveFamily: async () => {
      throw new Error("family unused")
    },
  }
}
