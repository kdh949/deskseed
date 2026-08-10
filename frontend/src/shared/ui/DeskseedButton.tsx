import { Button } from '@zendeskgarden/react-buttons'
import type { IButtonProps } from '@zendeskgarden/react-buttons'

/** Deskseed-owned entry point for Garden button primitives. */
export function DeskseedButton({ children, ...props }: IButtonProps) {
  return (
    <Button isPrimary {...props}>
      {children}
    </Button>
  )
}
