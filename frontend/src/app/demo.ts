import type { IconName } from '../components/Icon'

export const sections = ['Dashboard', 'Empresas', 'Sucursales', 'Usuarios', 'Aplicaciones'] as const
export type Section = typeof sections[number]
export type Directory = Exclude<Section, 'Dashboard'>
export type DemoItem = { id: string; name: string; caption: string; category: string; status: 'Activo' | 'Pendiente' | 'Publicado'; initials: string; details: [string, string][] }
export const icons: Record<Section, IconName> = { Dashboard: 'grid', Empresas: 'company', Sucursales: 'branch', Usuarios: 'users', Aplicaciones: 'apps' }
export const descriptions: Record<Directory, string> = {
  Empresas: 'Una vista organizada de las empresas y sus espacios de trabajo.',
  Sucursales: 'Cada ubicación, conectada a su empresa de referencia.',
  Usuarios: 'Las personas que dan vida a cada espacio de trabajo.',
  Aplicaciones: 'Un ecosistema de herramientas, una experiencia compartida.',
}
export const categoryLabels: Record<Directory, string> = { Empresas: 'Sector', Sucursales: 'Empresa', Usuarios: 'Equipo', Aplicaciones: 'Categoría' }
// Synthetic visual fixtures only. No persistence or authorization decisions.
export const demo: Record<Directory, DemoItem[]> = {
  Empresas: [
    { id: 'DEMO-EMP-01', name: 'Aurora Tecnología', caption: 'Santiago · Chile', category: 'Tecnología', status: 'Activo', initials: 'AT', details: [['Sucursales de muestra', '2'], ['Contacto ficticio', 'equipo@aurora.example']] },
    { id: 'DEMO-EMP-02', name: 'Norte Logística', caption: 'Antofagasta · Chile', category: 'Logística', status: 'Activo', initials: 'NL', details: [['Sucursales de muestra', '1'], ['Contacto ficticio', 'equipo@norte.example']] },
    { id: 'DEMO-EMP-03', name: 'Estudio Horizonte', caption: 'Valparaíso · Chile', category: 'Servicios', status: 'Pendiente', initials: 'EH', details: [['Sucursales de muestra', '1'], ['Contacto ficticio', 'equipo@horizonte.example']] },
  ],
  Sucursales: [
    { id: 'DEMO-SUC-01', name: 'Casa matriz Santiago', caption: 'Santiago · Región Metropolitana', category: 'Aurora Tecnología', status: 'Activo', initials: 'SA', details: [['Tipo de ubicación', 'Casa matriz']] },
    { id: 'DEMO-SUC-02', name: 'Oficina Providencia', caption: 'Providencia · Región Metropolitana', category: 'Aurora Tecnología', status: 'Activo', initials: 'PR', details: [['Tipo de ubicación', 'Oficina']] },
    { id: 'DEMO-SUC-03', name: 'Centro Antofagasta', caption: 'Antofagasta · Región de Antofagasta', category: 'Norte Logística', status: 'Activo', initials: 'AN', details: [['Tipo de ubicación', 'Centro de operaciones']] },
    { id: 'DEMO-SUC-04', name: 'Estudio Valparaíso', caption: 'Valparaíso · Región de Valparaíso', category: 'Estudio Horizonte', status: 'Pendiente', initials: 'VA', details: [['Tipo de ubicación', 'Estudio']] },
  ],
  Usuarios: [
    { id: 'DEMO-USR-01', name: 'Elena Torres', caption: 'elena@aurora.example', category: 'Operaciones', status: 'Activo', initials: 'ET', details: [['Empresa de muestra', 'Aurora Tecnología']] },
    { id: 'DEMO-USR-02', name: 'Mateo Silva', caption: 'mateo@norte.example', category: 'Logística', status: 'Activo', initials: 'MS', details: [['Empresa de muestra', 'Norte Logística']] },
    { id: 'DEMO-USR-03', name: 'Sofía Rojas', caption: 'sofia@horizonte.example', category: 'Diseño', status: 'Pendiente', initials: 'SR', details: [['Empresa de muestra', 'Estudio Horizonte']] },
    { id: 'DEMO-USR-04', name: 'Tomás Vidal', caption: 'tomas@aurora.example', category: 'Tecnología', status: 'Activo', initials: 'TV', details: [['Empresa de muestra', 'Aurora Tecnología']] },
  ],
  Aplicaciones: [
    { id: 'DEMO-APP-01', name: 'LS Core', caption: 'El centro de tu organización', category: 'Plataforma', status: 'Publicado', initials: 'LS', details: [['Versión de muestra', '4.0'], ['Descripción', 'Identidad visual y administración central.']] },
    { id: 'DEMO-APP-02', name: 'LS Operaciones', caption: 'Conecta equipos y procesos', category: 'Operaciones', status: 'Publicado', initials: 'OP', details: [['Versión de muestra', 'Concepto'], ['Descripción', 'Aplicación ficticia para explorar el diseño.']] },
    { id: 'DEMO-APP-03', name: 'LS Insights', caption: 'Una nueva perspectiva de tus datos', category: 'Analítica', status: 'Pendiente', initials: 'IN', details: [['Versión de muestra', 'Concepto'], ['Descripción', 'Aplicación ficticia para explorar el diseño.']] },
  ],
}
