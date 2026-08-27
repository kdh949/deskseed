import type { Meta, StoryObj } from '@storybook/react-vite'
import { DsPropertyField, DsSelect } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsPropertyField',
  component: DsPropertyField,
  parameters: {
    docs: {
      description: {
        component:
          'Ticket Properties처럼 label과 control을 수직으로 묶는 field composition이다. children은 실제 form control을 유지하며 label text는 control의 accessible name에 기여한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsPropertyField>

export default meta
type Story = StoryObj<typeof meta>

export const SelectField: Story = {
  args: {
    children: (
      <DsSelect defaultValue="Billing">
        <option>Billing</option>
        <option>Support</option>
      </DsSelect>
    ),
    label: 'Group',
  },
}

export const ReadOnlyField: Story = {
  args: {
    children: (
      <DsSelect defaultValue="Mina Park" disabled>
        <option>Mina Park</option>
      </DsSelect>
    ),
    label: 'Assignee',
  },
}
