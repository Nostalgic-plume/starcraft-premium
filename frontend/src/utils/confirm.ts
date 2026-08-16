import { useDialog } from 'naive-ui'

export interface ConfirmOptions {
  /** 标题，默认「提示」 */
  title?: string
  /** 正文内容 */
  content: string
  /** 确认按钮文案，默认「确定」 */
  positiveText?: string
  /** 取消按钮文案，默认「取消」 */
  negativeText?: string
}

/**
 * Promise 化的确认弹窗（Naive UI）
 * 确认返回 true，取消 / 关闭（X / 遮罩 / ESC）返回 false。
 * 需在 n-dialog-provider 内调用，主题随 n-config-provider 统一。
 */
export function useConfirm() {
  const dialog = useDialog()

  return {
    confirm(options: ConfirmOptions): Promise<boolean> {
      return new Promise(resolve => {
        dialog.warning({
          title: options.title || '提示',
          content: options.content,
          positiveText: options.positiveText || '确定',
          negativeText: options.negativeText || '取消',
          onPositiveClick: () => resolve(true),
          onNegativeClick: () => resolve(false),
          onClose: () => resolve(false)
        })
      })
    }
  }
}
