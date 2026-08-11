import { GardenPrimaryButton, type GardenButtonProps } from './garden'

/** Deskseed-owned entry point for Garden button primitives. */
export function DeskseedButton({ children, ...props }: GardenButtonProps) {
  return <GardenPrimaryButton {...props}>{children}</GardenPrimaryButton>
}
