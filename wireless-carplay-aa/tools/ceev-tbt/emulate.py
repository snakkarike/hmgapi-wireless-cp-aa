#!/usr/bin/env python3
"""Optional private-stock ARM integration test. No vehicle access or writes.

Executes the CEEV library's real routeMessage, string helpers and callback thunks.
Only JNI bodies, libc imports, logging and platform atomic helpers are mocked.
The synthetic input buffer has an unmapped trailing page and strict read bounds.
"""
from pathlib import Path
from io import BytesIO
import struct, os, sys, hashlib
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm_const import *
STOCK=Path(os.environ['CEEV_STOCK_LIBRARY'])
RECIPE=Path(__file__).resolve().parents[2]/'recipes/aa-tbt-dbfe7bd2.recipe'
REG=[UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3,UC_ARM_REG_R4,UC_ARM_REG_R5,UC_ARM_REG_R6,UC_ARM_REG_R7,UC_ARM_REG_R8,UC_ARM_REG_R9,UC_ARM_REG_R10,UC_ARM_REG_R11,UC_ARM_REG_R12]
def bw(a,t):
 off=t-a-4;s=int(off<0);i1=(off>>23)&1;i2=(off>>22)&1;j1=(~(i1^s))&1;j2=(~(i2^s))&1
 return struct.pack('<HH',0xf000|(s<<10)|((off>>12)&1023),0x9000|(j1<<13)|(j2<<11)|((off>>1)&2047))
class Machine:
 def __init__(self,patched=True,seg=0x300120):
  self.u=Uc(UC_ARCH_ARM,UC_MODE_THUMB);self.events=[];self.imports={};self.heap=0x500000;self.instruction_count=0
  u=self.u;u.mem_map(0x0,0x800000);u.mem_map(0x1000000,0x100000);u.mem_map(0x2000000,0x10000);u.mem_map(0xffff0000,0x10000);u.mem_map(0x3001000,0x1000)
  b=STOCK.read_bytes();assert hashlib.md5(b).hexdigest()=='dbfe7bd2e36152a50f97fed612cf6326';e=ELFFile(BytesIO(b))
  for s in e.iter_segments():
   if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
  symbols=e.get_section_by_name('.dynsym');self.symbols={s.name:s['st_value']for s in symbols.iter_symbols()}
  idx=0
  for sec in e.iter_sections():
   if sec['sh_type']!='SHT_REL':continue
   for r in sec.iter_relocations():
    typ=r['r_info_type'];at=r['r_offset'];sym=symbols.get_symbol(r['r_info_sym']);val=sym['st_value']
    if typ==23:continue # R_ARM_RELATIVE; zero load base
    if not val:
     val=0x2000000+idx*16;idx+=1
     self.imports[val]=sym.name
     if sym.name=='__stack_chk_guard':u.mem_write(val,struct.pack('<I',0x12345678))
    if typ in [21,22,2]:u.mem_write(at,struct.pack('<I',val))
    else:raise Exception(('relocation',typ,sym.name))
  if patched:
   lines=[x.split() for x in RECIPE.read_text().splitlines() if x and not x.startswith('#')]
   code=bytearray.fromhex(next(x[2] for x in lines if x[0]=='segment'))
   for x in lines:
    if x[0]=='rel32':struct.pack_into('<i',code,int(x[1],0),int(x[3],0)-(seg+int(x[2],0)))
   u.mem_write(seg,bytes(code))
   for x in lines:
    if x[0]=='patch':u.mem_write(int(x[1],0),bytes.fromhex(x[2]))
    if x[0]=='branch':u.mem_write(int(x[1],0),bw(int(x[1],0),seg+int(x[2],0)))
  u.hook_add(UC_HOOK_CODE,self.hook)
  u.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE,self.bounds)
 def bounds(self,u,access,a,size,value,user):
  if 0x3001000<=a<0x3002000:
   assert access==UC_MEM_READ, 'input buffer modified'
   assert self.input_start<=a and a+size<=self.input_end, ('input overread',hex(a),size)
 def read(self,a,size):
  if 0x3001000<=a<0x3002000:
   assert self.input_start<=a and a+size<=self.input_end, ('host input overread',hex(a),size)
  return self.u.mem_read(a,size)
 def w(self,a,*v):self.u.mem_write(a,struct.pack('<'+'I'*len(v),*[x&0xffffffff for x in v]))
 def r(self,a,n=1):return struct.unpack('<'+'I'*n,self.u.mem_read(a,4*n))
 def reg(self,n):return self.u.reg_read(REG[n])
 def ret(self,v=None):
  if v is not None:self.u.reg_write(UC_ARM_REG_R0,v&0xffffffff)
  self.u.reg_write(UC_ARM_REG_PC,self.u.reg_read(UC_ARM_REG_LR))
 def string(self,a):
  end,begin=self.r(a+0x10,2);assert 0<=end-begin<10000
  return bytes(self.u.mem_read(begin,end-begin)).decode('utf8','replace')
 def hook(self,u,a,size,user):
  self.instruction_count+=1
  if a in [0xffff0fa0]:self.ret();return
  if a==0xffff0fc0:
   old,new,at=[self.reg(k)for k in range(3)];ok=self.r(at)[0]==old
   if ok:self.w(at,new)
   self.ret(0 if ok else 1);return
  if a==0x200fff0:u.emu_stop();return
  if a==0xb3dfc:
   self.events.append(('version',bytes(u.mem_read(self.reg(1),self.reg(2))).hex()));self.ret(0);return
  if a in [0xa7190,0xa7128,0xa7162]:
   assert self.reg(0)==0x410000,('wrong this',hex(self.reg(0)))
   sp=u.reg_read(UC_ARM_REG_SP)
   signed=lambda x:struct.unpack('<i',struct.pack('<I',x))[0]
   if a==0xa7190:
    st,angle,num=self.r(sp,3);self.events.append(('turn',self.string(self.reg(1)),signed(self.reg(2)),signed(self.reg(3)),signed(angle),signed(num),self.string(st)))
   elif a==0xa7128:self.events.append(('distance',signed(self.reg(1)),signed(self.reg(2)),signed(self.reg(3)),self.r(sp)[0]))
   else:self.events.append(('status',self.reg(1)))
   self.ret();return
  if a in self.imports:
   n=self.imports[a]
   if n in ['memcpy','memmove']:
    d,s,z=[self.reg(k)for k in range(3)];u.mem_write(d,bytes(self.read(s,z)));self.ret(d)
   elif n=='memset':
    d,v,z=[self.reg(k)for k in range(3)];u.mem_write(d,bytes([v&255])*z);self.ret(d)
   elif n in ['_Znwj','_Znaj','malloc','_ZNSt12__node_alloc11_M_allocateERj']:
    z=self.r(self.reg(0))[0] if n.endswith('ERj') else self.reg(0)
    assert 0<z<0x100000,(n,z);d=self.heap;self.heap+=(z+15)&~15;self.ret(d)
   elif n in ['_ZdlPv','_ZdaPv','free','_ZNSt12__node_alloc13_M_deallocateEPvj']:self.ret()
   elif n in ['strlen']:
    z=0
    while self.read(self.reg(0)+z,1)!=b'\0':z+=1
    self.ret(z)
   elif n in ['__android_log_print','__android_log_buf_print','pthread_mutex_init','pthread_mutex_lock','pthread_mutex_unlock','pthread_mutex_destroy','__cxa_atexit','fprintf','fflush']:self.ret(0)
   else:raise Exception(('unhandled import',n,hex(a),hex(u.reg_read(UC_ARM_REG_LR))))
 def call(self,addr,args,stack=()):
  u=self.u;sp=0x1080000
  u.reg_write(UC_ARM_REG_SP,sp);u.reg_write(UC_ARM_REG_LR,0x200fff1)
  for k in range(4):u.reg_write(REG[k],args[k] if len(args)>k else 0)
  for k in range(4,12):u.reg_write(REG[k],0x12340000+k)
  self.w(sp,*stack)
  try:u.emu_start(addr|1,0,count=2000000)
  except Exception as e:raise RuntimeError(f'{e}, at {u.reg_read(UC_ARM_REG_PC):x}')from e
  assert u.reg_read(UC_ARM_REG_PC)==0x200fff0,('did not return',hex(u.reg_read(UC_ARM_REG_PC)))
  assert u.reg_read(UC_ARM_REG_SP)==sp
  for k in range(4,12):assert u.reg_read(REG[k])==0x12340000+k,('clobbered',k,hex(u.reg_read(REG[k])))
  return self.reg(0)
 def route(self,typ,payload,declared=None):
  # Original routeMessage ABI: r0 endpoint, r1 channel, r2 type,
  # r3 shared_ptr<IoBuffer>. Buffer includes a two-byte big-endian type.
  self.w(0x40002c,0x410010);self.w(0x410010,0x420000)
  self.w(0x420008,0xa7189,0xa7211,0xa715b)
  self.w(0x430004,0x440000)
  wire=struct.pack('>H',typ)+payload;self.input_end=0x3002000;self.input_start=0x3002000-len(wire);self.u.mem_write(self.input_start,wire)
  self.w(0x440000,self.input_start,0,0,len(wire) if declared is None else declared)
  result=self.call(0xb7e50,[0x400000,9,typ,0x430000])
  return result,self.events

def varint(n):
    out = bytearray()
    while n >= 128:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])


def field(number, value):
    if isinstance(value, bytes):
        return varint(number * 8 + 2) + varint(len(value)) + value
    return varint(number * 8) + varint(value)


def notification(road):
    # Synthetic modern NavigationNotification: normal right, road, destination.
    # Wire layout cross-checked with open-android-auto's NavigationNotificationMessage.proto.
    step = field(1, field(1, 8)) + field(4, field(1, road))
    return field(1, step) + field(2, field(1, b'Home'))


def whole(road):
    # at most 96 bytes, cut on a whole UTF-8 character (the parser's string cut, tools/aa-text)
    n = min(len(road), 96)
    while len(road) > 96 and n > 0 and road[n] & 0xc0 == 0x80:
        n -= 1
    return road[:n].decode()


def run():
    checks = 0
    for patched, packet in [(False, '000100010004'), (True, '000100010006')]:
        machine = Machine(patched)
        assert machine.call(0xb3e88, [0x400000]) == 0
        assert machine.events == [('version', packet)]
        checks += 1
    for patched in (False, True):
        for status in (0, 1, 2, 3, 4):
            machine = Machine(patched)
            result, events = machine.route(0x8003, field(1, status))
            valid = status <= (3 if patched else 2)
            assert events == ([('status', status)] if valid else [])
            assert result == (0 if valid else 0xffffff03)
            checks += 1
        legacy_turn = field(1, b'Main Street') + field(2, 2) + field(3, 4) + field(5, 0) + field(6, 90)
        machine = Machine(patched)
        assert machine.route(0x8004, legacy_turn) == (0, [('turn', 'Main Street', 2, 4, 90, 0, '')])
        machine = Machine(patched)
        legacy_distance = field(1, 100) + field(2, 10) + field(3, 100) + field(4, 1)
        assert machine.route(0x8005, legacy_distance) == (0, [('distance', 100, 10, 100, 1)])
        checks += 2
    for segment in (0x1000, 0x1c4120, 0x300120, 0x700120):
        for road in (b'Main Street', b'A' * 512, '무령로'.encode(), 'Allée'.encode(),
                     b'A' * 95 + 'é'.encode(), ('가' * 40).encode()):
            machine = Machine(seg=segment)
            result, events = machine.route(0x8006, notification(road))
            assert result == 0
            assert ('turn', whole(road), 2, 4, -1, -1, '') in events
            assert ('turn', 'Home', 0, 32, -1, 0, '') in events
            checks += 1
        machine = Machine(seg=segment)
        assert machine.route(0x8008, b'') == (0xffffff03, [])
        checks += 1
    distance = field(1, 100) + field(2, b'100') + field(3, 1)
    trip = field(1, 5000) + field(2, b'5.0') + field(3, 3)
    position = field(1, field(1, distance) + field(2, 10)) + field(2, field(1, trip) + field(3, 600))
    machine = Machine()
    result, events = machine.route(0x8007, position)
    assert result == 0
    assert events[1:] == [('distance', 100, 0, 0, 1), ('distance', 5000, 600, 0, 0x903)]
    checks += 1
    # Truncated/overflowing varints, impossible nested lengths and unsupported wire types.
    malformed = [b'\x80', b'\x80' * 10, b'\xff' * 12, b'\x0a', b'\x0a\x7f',
                 b'\x0a\xff\xff\xff\xff\x0f', b'\x0a\x01\x80', b'\x0f',
                 b'\x0a\x03\x0a\x7f\x00', b'\x12\x02\x0a\x7f',
                 b'\x0a\x04\x0a\x02\x08\x80']
    # Every prefix of a normal message includes truncation inside nested fields and text.
    message = notification(b'Elm Road')
    malformed += [message[:n] for n in range(len(message))]
    for message_type in (0x8006, 0x8007):
        for payload in malformed:
            machine = Machine()
            result, events = machine.route(message_type, payload)
            assert result in (0, 0xffffffff)
            checks += 1
        for declared in (0, 1, 0xffffffff):
            machine = Machine()
            result, events = machine.route(message_type, b'', declared)
            assert result in (0, 0xffffffff)
            checks += 1
    print('CEEV ARM integration: %d scenarios passed (off-car only)' % checks)


if __name__ == '__main__':
    run()
